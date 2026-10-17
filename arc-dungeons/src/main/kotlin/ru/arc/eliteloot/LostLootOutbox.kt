package ru.arc.eliteloot

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.logging.ArcLogging as Logging
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Native bytes are captured once on Paper; encoding and every journal operation belong here. */
internal data class LostLootCapture(
    val id: String,
    val owner: String,
    val bytes: ByteArray,
    val capturedAt: Long,
    val nativePrice: Double?,
)

/** The continuity listener retains only this memory index, never a worker, SQL lease or Item. */
internal class LostLootIndex {
    internal data class Entry(val record: LostLootRecord, val certain: Boolean)
    private val values = ConcurrentHashMap<String, Entry>()
    fun get(id: String): Entry? = values[id]
    internal fun put(record: LostLootRecord, certain: Boolean) { values[record.id] = Entry(record, certain) }
}

/**
 * Single FIFO owner of the local truth. SQL availability never precedes local capture in this
 * lane. The lock-free index is only an immutable certainty projection, never a second store.
 */
internal class LostLootOutbox(
    private val open: () -> LostLootJournal,
    private val publish: (LostLootRecord) -> CompletableFuture<Unit>,
    private val worker: BoundedAsyncExecutor = BoundedAsyncExecutor("lost-loot-outbox"),
) : AutoCloseable {
    enum class State { STARTING, READY, FAILED, CLOSED }

    val index = LostLootIndex()
    private val initialized = CompletableFuture<Void>()
    private val closed = AtomicBoolean()
    private val pumping = AtomicBoolean()
    private val started = AtomicBoolean()
    private var journal: LostLootJournal? = null // worker only
    private val publishing = linkedMapOf<String, Pair<LostLootRecord, CompletableFuture<Unit>>>() // worker only, <= 4
    private val incidents = mutableSetOf<String>() // worker only
    @Volatile var state = State.STARTING
        private set
    private var completion: CompletableFuture<Void>? = null

    fun start(): CompletableFuture<Void> {
        if (started.compareAndSet(false, true)) {
            worker.submit {
                val store = open()
                journal = store
                for (record in store.records()) index.put(record, store.certain(record.id))
                if (!closed.get()) state = State.READY
            }.whenComplete { _, failure ->
                if (failure == null) initialized.complete(null)
                else { if (!closed.get()) state = State.FAILED; initialized.completeExceptionally(failure) }
            }
        }
        return initialized.copy()
    }

    fun ready(): CompletableFuture<Void> = initialized.copy()
    fun entry(id: String): LostLootIndex.Entry? = index.get(id)

    fun capture(snapshot: LostLootCapture): CompletableFuture<LostLootRecord> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Lost loot outbox is closed"))
        return worker.submit {
            val store = checkNotNull(journal) { "Lost loot journal is unavailable" }
            val previous = store.get(snapshot.id)
            if (previous != null && store.certain(snapshot.id)) return@submit previous
            val record = previous ?: LostLootRecord(
                snapshot.id, snapshot.owner, Base64.getEncoder().encodeToString(snapshot.bytes),
                snapshot.capturedAt, nativePrice = snapshot.nativePrice,
            )
            write(store, record)
        }
    }

    /** Coalesced bounded admission. A rejected pulse is diagnostic; all durable records remain pending. */
    fun publishPending(): CompletableFuture<Unit> {
        if (closed.get() || !pumping.compareAndSet(false, true)) return CompletableFuture.completedFuture(Unit)
        return worker.submit {
            val store = journal ?: return@submit
            finishPublications(store, wait = false)
            for (record in store.pending(4)) {
                if (record.id in publishing || publishing.size >= 4) continue
                publishing[record.id] = record to try { publish(record) }
                    catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
            }
            finishPublications(store, wait = false)
        }.whenComplete { _, failure ->
            pumping.set(false)
            if (failure != null) Logging.error("LostEliteLoot outbox pulse rejected/failed; durable records retained", failure)
        }
    }

    private fun finishPublications(store: LostLootJournal, wait: Boolean) {
        val cursor = publishing.iterator()
        while (cursor.hasNext()) {
            val (_, pending) = cursor.next()
            val (record, result) = pending
            if (!wait && !result.isDone) continue
            try {
                // Only this worker can wait. Shutdown keeps the SQL lease alive through this result.
                result.join()
            } catch (failure: Throwable) {
                incident("publish:${record.id}", failure)
                cursor.remove() // STORED remains durable and is retried by the next pulse/generation.
                continue
            }
            try {
                val current = store.get(record.id)
                if (current?.state == LostLootState.STORED) write(store, current.copy(state = LostLootState.EXPORTED, item = null))
                if (incidents.remove("publish:${record.id}")) Logging.info("LostEliteLoot recovered export={}", record.id)
                cursor.remove()
            } catch (failure: Throwable) {
                incident("publish:${record.id}", failure)
                // Retain successful SQL acknowledgement until its local tombstone is verified.
                if (wait) throw failure
            }
        }
    }

    private fun write(store: LostLootJournal, record: LostLootRecord): LostLootRecord {
        // Publish uncertainty BEFORE the first write. Readback/rename failure cannot enable pickup.
        index.put(record, false)
        val saved = store.write(record)
        index.put(saved, true)
        return saved
    }

    private fun incident(key: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("LostEliteLoot operation={} retry=retained", key, failure)
    }

    /** Finalizer also owns accepted cold SQL acquisition and lease disposal, supplied by the module. */
    fun closeAsync(finalizer: () -> Unit = {}): CompletableFuture<Void> {
        completion?.let { return it.copy() }
        closed.set(true)
        state = State.CLOSED
        val result = CompletableFuture<Void>()
        completion = result
        worker.closeAsync {
            var failure: Throwable? = null
            try { journal?.let { finishPublications(it, wait = true) } } catch (error: Throwable) { failure = error }
            try { finalizer() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            failure?.let { throw it }
        }.whenComplete { _, failure ->
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        }
        return result.copy()
    }

    override fun close() { closeAsync() }
}
