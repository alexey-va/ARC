package ru.arc.audit

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun interface AuditBatchSink {
    fun write(events: List<AuditEvent>): CompletableFuture<List<AuditAppendResult>>
}

class AuditBufferFullException(maximumPendingEvents: Int) :
    IllegalStateException("Audit SQL buffer reached its bounded capacity of $maximumPendingEvents events")

/**
 * Bounded producer buffer. Producers only enqueue; [flush] is invoked by the
 * store's scheduler and delegates JDBC work to the SQL executor-backed sink.
 */
internal class AuditWriteBatcher(
    private val maximumPendingEvents: Int,
    private val batchSize: Int,
    private val sink: AuditBatchSink,
) {
    private data class Pending(
        val event: AuditEvent,
        val completion: CompletableFuture<AuditAppendResult>,
    )

    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val accepted = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private var inFlight: CompletableFuture<Unit>? = null

    init {
        require(maximumPendingEvents > 0) { "Maximum pending audit events must be positive" }
        require(batchSize in 1..maximumPendingEvents) { "Audit batch size must fit the pending-event bound" }
    }

    val pendingCount: Int get() = accepted.get()

    fun append(event: AuditEvent): CompletableFuture<AuditAppendResult> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("Audit writer is closed"))
        if (!reserve()) return CompletableFuture.failedFuture(AuditBufferFullException(maximumPendingEvents))
        val completion = CompletableFuture<AuditAppendResult>()
        synchronized(lock) {
            if (closed.get()) {
                accepted.decrementAndGet()
                return CompletableFuture.failedFuture(IllegalStateException("Audit writer is closed"))
            }
            queue.addLast(Pending(event, completion))
        }
        return completion
    }

    fun flush(): CompletableFuture<Unit> {
        val batch: List<Pending>
        val flushCompletion: CompletableFuture<Unit>
        synchronized(lock) {
            inFlight?.let { return it }
            if (queue.isEmpty()) return CompletableFuture.completedFuture(Unit)
            batch = buildList {
                repeat(minOf(batchSize, queue.size)) { add(queue.removeFirst()) }
            }
            flushCompletion = CompletableFuture()
            inFlight = flushCompletion
        }

        val write =
            runCatching { sink.write(batch.map(Pending::event)) }
                .getOrElse { CompletableFuture.failedFuture(it) }
        write.whenComplete { results, failure ->
            if (failure == null && results.size == batch.size) {
                accepted.addAndGet(-batch.size)
                batch.zip(results).forEach { (pending, result) -> pending.completion.complete(result) }
                synchronized(lock) { inFlight = null }
                flushCompletion.complete(Unit)
            } else {
                synchronized(lock) {
                    batch.asReversed().forEach(queue::addFirst)
                    inFlight = null
                }
                val cause = failure ?: IllegalStateException("Audit SQL batch result size did not match request size")
                flushCompletion.completeExceptionally(cause)
            }
        }
        return flushCompletion
    }

    fun stopAccepting() {
        closed.set(true)
    }

    private fun reserve(): Boolean {
        while (true) {
            val current = accepted.get()
            if (current >= maximumPendingEvents) return false
            if (accepted.compareAndSet(current, current + 1)) return true
        }
    }
}
