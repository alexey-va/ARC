package ru.arc.rtp

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** The file store has one worker owner. Main-thread reads use detached durable state. */
internal class RtpRegistryRuntime(
    predecessor: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit),
    open: () -> RtpPlayerStore,
) {
    private val worker = BoundedAsyncExecutor("arc-rtp-state")
    private val pending = ConcurrentHashMap<UUID, Int>()
    @Volatile private var snapshot: RtpPlayerSnapshot? = null
    private val prepared = worker.submit {
        predecessor.join() // Worker only: do not reopen before the preceding generation drains.
        open().also { snapshot = it.snapshot() }
    }

    fun state(playerId: UUID, worldName: String): PlayerRtpState? =
        if (pending.containsKey(playerId)) null else snapshot?.state(playerId, worldName)

    fun <T> mutate(playerId: UUID, operation: (RtpPlayerStore) -> T): CompletableFuture<T> {
        pending.compute(playerId) { _, count -> (count ?: 0) + 1 }
        return worker.submit {
            val store = prepared.join() // Initialization precedes every operation on this FIFO.
            operation(store).also { snapshot = store.snapshot() }
        }.whenComplete { _, _ -> pending.compute(playerId) { _, count -> count?.takeIf { it > 1 }?.minus(1) } }
    }

    fun ready(): CompletableFuture<Unit> = prepared.thenApply { Unit }
    fun closeAsync(): CompletableFuture<Unit> = worker.closeAsync()
}

object RtpPlayerRegistry {
    @Volatile private var runtime: RtpRegistryRuntime? = null
    private var drained = CompletableFuture.completedFuture(Unit)

    internal fun readiness(): CompletableFuture<Unit> = runtime?.ready()
        ?: CompletableFuture.failedFuture(IllegalStateException("First-RTP registry is unavailable"))

    internal fun closeCompletion(): CompletableFuture<Unit> = drained.copy()

    fun initialize(pluginDataPath: Path) {
        shutdown()
        val current = RtpRegistryRuntime(drained) {
            RtpPlayerStore.open(pluginDataPath.resolve("data/rtp-players.json")).also {
                info("Loaded first-RTP registry: {} players across {} worlds", it.playerCount(), it.worldCount())
            }
        }
        runtime = current
        current.ready().whenComplete { _, failure ->
            if (failure != null) error("Could not load first-RTP registry; routing remains unavailable", failure)
        }
    }

    fun state(
        playerId: UUID,
        worldName: String,
    ): PlayerRtpState? = runtime?.state(playerId, worldName)

    fun markTeleported(
        playerId: UUID,
        worldName: String,
    ): CompletableFuture<Boolean> = runtime?.mutate(playerId) { it.markTeleported(playerId, worldName) }
        ?: CompletableFuture.failedFuture(IllegalStateException("First-RTP registry is unavailable"))

    fun reset(
        playerId: UUID,
        worldName: String? = null,
    ): CompletableFuture<PlayerRtpResetResult> = runtime?.mutate(playerId) { it.reset(playerId, worldName) }
        ?: CompletableFuture.failedFuture(IllegalStateException("First-RTP registry is unavailable"))

    fun shutdown() {
        val previous = runtime
        runtime = null
        if (previous != null) drained = previous.closeAsync()
    }
}
