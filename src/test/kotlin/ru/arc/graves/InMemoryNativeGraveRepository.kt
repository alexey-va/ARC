package ru.arc.graves

import ru.arc.network.BackendServerId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/** Deterministic test-only implementation, including the shared pending-owner constraint. */
internal class InMemoryNativeGraveRepository : NativeGraveRepository {
    private val records = linkedMapOf<UUID, NativeGraveRecord>()
    private val sequences = mutableMapOf<UUID, Pair<UUID, Long>>()
    private val watermarks = mutableMapOf<UUID, NativeGraveWatermark>()
    private var lastSequence = 0L
    private var closed = false

    override fun loadAll(): CompletableFuture<List<NativeGraveRecord>> = execute { records.values.toList() }

    override fun loadCurrent(server: String, owners: Set<UUID>, now: Long): CompletableFuture<List<NativeGraveRecord>> = execute {
        require(BackendServerId.parseOrNull(server) != null) { "Grave query server is invalid" }
        require(now >= 0) { "Grave query time is invalid" }
        records.values.filter { record ->
            (record.server == server || record.ownerId in owners) && when (record.phase) {
                NativeGravePhase.ACTIVE -> record.expiresAt < 0 || record.expiresAt > now
                NativeGravePhase.CLAIMED -> false
                else -> true
            }
        }
    }

    override fun loadWatermarks(owners: Set<UUID>): CompletableFuture<Map<UUID, NativeGraveWatermark>> = execute {
        watermarks.filterKeys { it in owners }
    }

    override fun reserveSequence(operationId: UUID, ownerId: UUID): CompletableFuture<Long> = execute {
        sequences[operationId]?.let { (owner, sequence) ->
            check(owner == ownerId) { "Native grave operation belongs to another owner" }
            return@execute sequence
        }
        check(lastSequence < Long.MAX_VALUE) { "Native grave operation sequence is exhausted" }
        (++lastSequence).also { sequences[operationId] = ownerId to it }
    }

    override fun insert(record: NativeGraveRecord): CompletableFuture<Boolean> = execute {
        record.validate()
        val previous = records[record.id]
        if (previous != null) return@execute previous == record
        if (ownerBusy(record)) return@execute false
        records[record.id] = record
        true
    }

    override fun replace(expected: NativeGraveRecord, next: NativeGraveRecord): CompletableFuture<Boolean> = execute {
        require(expected.id == next.id) { "Grave replacement id differs" }
        require(expected.revision < Long.MAX_VALUE && next.revision == expected.revision + 1) {
            "Grave replacement revision must advance exactly once"
        }
        expected.validate()
        next.validate()
        if (records[expected.id] != expected || ownerBusy(next)) return@execute false
        if (next.completedSequence > expected.completedSequence && next.completedSequence > (watermarks[next.ownerId]?.sequence ?: 0)) {
            watermarks[next.ownerId] = NativeGraveWatermark(next.ownerId, next.completedSequence, requireNotNull(next.checkpoint))
        }
        records[next.id] = next
        true
    }

    private fun ownerBusy(record: NativeGraveRecord): Boolean {
        val pendingOwner = record.pendingOwnerId() ?: return false
        return records.values.any { it.id != record.id && it.pendingOwnerId() == pendingOwner }
    }

    @Synchronized private fun <T> execute(operation: () -> T): CompletableFuture<T> = runCatching {
        if (closed) throw RejectedExecutionException("Native grave repository is closed")
        operation()
    }.fold(
        onSuccess = { CompletableFuture.completedFuture(it) },
        onFailure = { CompletableFuture.failedFuture(it) },
    )

    @Synchronized override fun closeAsync(): CompletableFuture<Unit> {
        closed = true
        return CompletableFuture.completedFuture(Unit)
    }
}
