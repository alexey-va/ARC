package ru.arc.itemcatalog

import com.google.gson.Gson
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal fun persistPhysicalArchivesForTest(rewards: CatalogPhysicalRewards): Boolean =
    rewards.persistPhysicalArchives(
        requireNotNull(rewards.capturePhysicalArchives()),
        TestSharedPhysicalRewardArchiveRepository(),
    ).failureCause == null

/** Small deterministic publisher for legacy map tests that exercise only local materialization. */
internal fun persistInteractiveArchivesForTest(
    rewards: CatalogPhysicalRewards,
    snapshots: List<FrozenPhysicalPrepared>,
): Boolean = rewards.persistPhysicalArchives(
    PhysicalRewardArchiveCapture(snapshots, complete = true),
    TestSharedPhysicalRewardArchiveRepository(),
).failureCause == null

private class TestSharedPhysicalRewardArchiveRepository : SharedPhysicalRewardArchiveRepository {
    private val payloads = ConcurrentHashMap<String, ByteArray>()

    override fun publishBatch(records: List<FrozenPhysicalRewardRecord>): CompletableFuture<Unit> = runCatching {
        records.forEach { record ->
            val bytes = Gson().toJson(record).toByteArray(Charsets.UTF_8)
            val previous = payloads.putIfAbsent(record.fingerprint, bytes)
            check(previous == null || previous.contentEquals(bytes)) { "Immutable test archive collision" }
        }
        Unit
    }.fold(
        onSuccess = { CompletableFuture.completedFuture(it) },
        onFailure = { CompletableFuture.failedFuture(it) },
    )

    override fun fetch(fingerprint: String): CompletableFuture<ByteArray?> =
        CompletableFuture.completedFuture(payloads[fingerprint]?.clone())

    override fun status(): SharedPhysicalRewardArchiveStatus = SharedPhysicalRewardArchiveStatus(ready = true)

    override fun close() = Unit
}
