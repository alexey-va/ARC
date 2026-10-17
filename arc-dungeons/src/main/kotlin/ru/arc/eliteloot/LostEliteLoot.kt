package ru.arc.eliteloot

import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.logging.ArcLogging as Logging
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal fun lostLootMobSource(source: String?, template: String): Boolean {
    val parts = template.split("\$mob", limit = 2)
    return source != null && parts.size == 2 && source.startsWith(parts[0]) && source.endsWith(parts[1]) &&
        source.length > parts[0].length + parts[1].length
}

/** Native capture is sliced; only a verified journal record permits removal of that exact entity. */
internal class LostEliteLoot(
    private val server: Server,
    private val outbox: LostLootOutbox,
    private val nativeWork: BudgetedTaskQueue,
    private val nativeCleanup: ArcNativeCleanupService,
    private val tickets: PaperChunkTicketRegistry,
    private val protection: LostLootProtection,
    private val isElite: (ItemStack) -> Boolean,
    private val mobSource: () -> String,
    private val nativePrice: (ItemStack) -> Double? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) : Listener, AutoCloseable {
    private class Tracked(
        val item: Item, val key: PaperChunkKey, val chunkEpoch: Long, val worldEpoch: Long,
        var requested: Boolean = false,
    ) {
        var lease: PaperChunkTicketLease? = null
        var pending: CompletableFuture<LostLootRecord>? = null
        var retryAt = 0L
    }
    private class WorldCapture { var scanned = false; var scan: CompletableFuture<Unit>? = null }
    private val tasks = LifecycleTaskScope()
    private var tracked = ConcurrentHashMap<UUID, Tracked>()
    private var quick = ConcurrentHashMap<UUID, Tracked>()
    private val chunkEpochs = mutableMapOf<PaperChunkKey, Long>()
    private val worldEpochs = mutableMapOf<UUID, Long>()
    private val worldCounts = mutableMapOf<UUID, Int>()
    private val worldCaptures = mutableMapOf<UUID, WorldCapture>()
    private val departed = mutableSetOf<UUID>()
    private val incidents = mutableSetOf<String>()
    private var ticketFailure: Throwable? = null
    private var sweep: CompletableFuture<Unit>? = null
    private var quickSweep: CompletableFuture<Unit>? = null
    private var closed = false
    private var started = false
    private val ready = CompletableFuture<Void>()
    private var completion: CompletableFuture<Void>? = null

    /** Called only after both the continuity guard and this consumer listener are registered. */
    fun start(): CompletableFuture<Void> {
        if (started) return ready.copy()
        started = true
        protection.starting()
        try {
            outbox.start().join()
            protection.attach(outbox.index)
            ready.complete(null)
        } catch (failure: Throwable) {
            ready.completeExceptionally(failure)
            Logging.error("LostEliteLoot local journal unavailable; bound loot remains protected", failure)
        }
        scanLoadedWorlds()
        tasks.runTimer(20, 20) { scheduleSweep(false); outbox.publishPending() }
        tasks.runTimer(1, 1) { if (quick.isNotEmpty()) scheduleSweep(true) }
        return ready.copy()
    }

    fun ready(): CompletableFuture<Void> = ready.copy()

    internal fun owner(item: Item): UUID? {
        val owner = nativeBoundOwner(item) ?: return null
        val stack = item.itemStack
        if (!isElite(stack)) return null
        return owner.takeIf {
            lostLootMobSource(stack.itemMeta?.persistentDataContainer?.get(lostLootSourceKey, PersistentDataType.STRING), mobSource())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun manuallyDropped(event: PlayerDropItemEvent) {
        event.itemDrop.persistentDataContainer.set(lostLootManualKey, PersistentDataType.BYTE, 1)
        tracked[event.itemDrop.uniqueId]?.let { forget(event.itemDrop.uniqueId, it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun spawned(event: ItemSpawnEvent) {
        if (closed) return
        // Source/soulbind metadata is finished after this event. Inspect it on the next native slice.
        track(event.entity)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun ownerLeft(event: PlayerQuitEvent) {
        if (!closed) { departed.add(event.player.uniqueId); scheduleSweep(false) }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun ownerJoined(event: PlayerJoinEvent) { departed.remove(event.player.uniqueId) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun despawn(event: ItemDespawnEvent) {
        if (closed || (owner(event.entity) == null && !protection.locked(event.entity))) return
        event.isCancelled = true
        capture(event.entity)
    }

    /** Admission only; no encoding, disk access or worker wait is performed by this callback. */
    internal fun capture(item: Item) {
        if (closed || item.isDead || !item.isValid) return
        track(item, requested = true)?.let { it.retryAt = 0 }
    }

    private fun track(
        item: Item,
        key: PaperChunkKey = item.location.let { PaperChunkKey(item.world.uid, it.blockX shr 4, it.blockZ shr 4) },
        epoch: Long = chunkEpochs[key] ?: 0,
        requested: Boolean = false,
    ): Tracked? {
        if (closed) return null
        val id = item.uniqueId
        tracked[id]?.let { old ->
            if (old.item === item && old.chunkEpoch == epoch && old.worldEpoch == (worldEpochs[key.worldId] ?: 0L)) {
                old.requested = old.requested || requested
                quick[id] = old
                return old
            }
            forget(id, old)
        }
        val value = Tracked(item, key, epoch, worldEpochs[key.worldId] ?: 0, requested)
        tracked[id] = value
        quick[id] = value
        worldCounts[key.worldId] = (worldCounts[key.worldId] ?: 0) + 1
        return value
    }

    private fun scheduleSweep(fast: Boolean) {
        if (closed || outbox.state == LostLootOutbox.State.STARTING) return
        if ((if (fast) quickSweep else sweep)?.isDone == false) return
        val cursor = (if (fast) quick else tracked).entries.iterator()
        val result = nativeWork.submit({ !closed }) {
            if (!cursor.hasNext()) true
            else { val entry = cursor.next(); process(entry.key, entry.value); false }
        }
        if (fast) quickSweep = result else sweep = result
        result.whenComplete { _, failure ->
            if (failure != null && !closed && failure !is CancellationException)
                Logging.error("LostEliteLoot native sweep failed; items retained", failure)
        }
    }

    private fun current(id: UUID, entry: Tracked): Boolean = tracked[id] === entry &&
        (chunkEpochs[entry.key] ?: 0) == entry.chunkEpoch &&
        (worldEpochs[entry.key.worldId] ?: 0) == entry.worldEpoch

    private fun process(id: UUID, entry: Tracked) {
        if (tracked[id] !== entry) return
        // The unload callback invalidates the epoch without retaining/reading its old entity list.
        if (!current(id, entry)) { forget(id, entry); return }
        val world = server.getWorld(entry.key.worldId)
        if (world == null || !world.isChunkLoaded(entry.key.x, entry.key.z)) { forget(id, entry); return }
        val item = entry.item
        if (item.isDead || !item.isValid) { forget(id, entry); return }
        val position = item.location
        val actualKey = PaperChunkKey(item.world.uid, position.blockX shr 4, position.blockZ shr 4)
        if (actualKey != entry.key) {
            // Motion/teleports transfer retention before any new snapshot is admitted. A late
            // completion belongs to the old identity; the shared UUID index reconciles this one.
            track(item, actualKey, requested = entry.requested)
            return
        }
        val record = outbox.entry(id.toString())
        if (record?.certain == true) { item.remove(); forget(id, entry); return }
        val owner = record?.record?.owner?.let(UUID::fromString) ?: owner(item)
        if (owner == null) {
            if (protection.locked(item)) return // Unknown/corrupt owned record stays protected, never silently released.
            forget(id, entry)
            return
        }
        if (entry.lease == null) {
            // addPluginChunkTicket may load a chunk. The isChunkLoaded check and acquire run in
            // this same native quantum, strictly before the unload event can interleave.
            when (val acquired = tickets.acquire(entry.key)) {
                is PaperChunkTicketAcquireResult.Acquired -> entry.lease = acquired.lease
                is PaperChunkTicketAcquireResult.Failed -> { incident("ticket:$id", acquired.failure); return }
                else -> return
            }
        }
        entry.pending?.let { pending ->
            if (!pending.isDone) return
            entry.pending = null
            try {
                pending.join() // Already completed; never waits for a worker-held lock or I/O.
                if (current(id, entry) && outbox.entry(id.toString())?.certain == true) {
                    item.remove(); forget(id, entry); recovered("capture:$id")
                }
            } catch (failure: Throwable) {
                incident("capture:$id", failure)
                entry.retryAt = now() + 30_000L
            }
            return
        }
        if (outbox.state != LostLootOutbox.State.READY || now() < entry.retryAt) return
        val player = server.getPlayer(owner)
        val abandoned = owner in departed || player == null || !player.isOnline || player.world.uid != entry.key.worldId ||
            player.location.distanceSquared(item.location) > 64.0 * 64.0
        if (!entry.requested && entry.key.worldId !in worldCaptures && !abandoned) { quick.remove(id, entry); return }
        try {
            freeze(item)
            val stack = item.itemStack.clone()
            val snapshot = LostLootCapture(id.toString(), owner.toString(), stack.serializeAsBytes(), now(), nativePrice(stack))
            entry.pending = outbox.capture(snapshot)
            quick[id] = entry
        } catch (failure: Throwable) {
            incident("capture:$id", failure)
            entry.retryAt = now() + 30_000L
        }
    }

    private fun freeze(item: Item) {
        item.persistentDataContainer.set(lostLootCaptureKey, PersistentDataType.BYTE, 1)
        item.setCanPlayerPickup(false); item.setCanMobPickup(false); item.setWillAge(false)
        item.isInvulnerable = true; item.setGravity(false); item.velocity = org.bukkit.util.Vector()
    }

    private fun forget(id: UUID, entry: Tracked) {
        if (!tracked.remove(id, entry)) return
        quick.remove(id, entry)
        worldCounts.compute(entry.key.worldId) { _, count -> ((count ?: 1) - 1).takeIf { it > 0 } }
        try { entry.lease?.close() } catch (failure: Throwable) {
            if (ticketFailure == null) ticketFailure = failure
            incident("ticket-release:$id", failure)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun loaded(event: EntitiesLoadEvent) {
        if (closed) return
        val key = PaperChunkKey(event.world.uid, event.chunk.x, event.chunk.z)
        val epoch = (chunkEpochs[key] ?: 0) + 1
        chunkEpochs[key] = epoch
        val worldEpoch = worldEpochs[key.worldId] ?: 0
        val cursor = event.entities.iterator()
        nativeWork.submit({ !closed && chunkEpochs[key] == epoch && (worldEpochs[key.worldId] ?: 0) == worldEpoch }) {
            if (!cursor.hasNext() || !event.world.isChunkLoaded(key.x, key.z)) true
            else { val entity = cursor.next(); if (entity is Item) track(entity, key, epoch); false }
        }.whenComplete { _, failure ->
            if (failure != null && failure !is CancellationException && !closed)
                Logging.error("LostEliteLoot loaded-entity reconciliation was not admitted", failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun entitiesUnloading(event: EntitiesUnloadEvent) {
        if (closed) return
        val key = PaperChunkKey(event.world.uid, event.chunk.x, event.chunk.z)
        chunkEpochs[key] = (chunkEpochs[key] ?: 0) + 1
        // Paper emits this after entities become inaccessible, before ChunkUnloadEvent (which
        // is not cancellable). Never serialize/capture or retain that stale list asynchronously.
        // Vanilla retains the physical copy; the UUID index suppresses it on a later load.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun worldUnloading(event: WorldUnloadEvent) {
        if (closed) return
        val id = event.world.uid
        val barrier = worldCaptures.getOrPut(id) { WorldCapture() }
        if (barrier.scanned && (worldCounts[id] ?: 0) == 0) return
        event.isCancelled = true
        if (barrier.scan == null || barrier.scan?.isCompletedExceptionally == true) {
            val epoch = worldEpochs[id] ?: 0
            barrier.scan = scanWorld(event.world, force = true)
            barrier.scan!!.whenCompleteSync(tasks) { _, failure ->
                if (!closed && worldCaptures[id] === barrier && (worldEpochs[id] ?: 0) == epoch) {
                    if (failure == null) { barrier.scanned = true; scheduleSweep(false) }
                    else incident("world:$id", failure)
                }
            }
        }
        scheduleSweep(false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun worldUnloaded(event: WorldUnloadEvent) { worldEpochs[event.world.uid] = (worldEpochs[event.world.uid] ?: 0) + 1 }
    @EventHandler(priority = EventPriority.MONITOR)
    fun worldLoaded(event: WorldLoadEvent) { worldCaptures.remove(event.world.uid) }

    private fun scanLoadedWorlds() {
        val worlds = server.worlds.iterator()
        nativeWork.submit({ !closed }) {
            if (!worlds.hasNext()) true
            else { scanWorld(worlds.next()); false }
        }
    }

    private fun scanWorld(world: World, force: Boolean = false): CompletableFuture<Unit> {
        val worldEpoch = worldEpochs[world.uid] ?: 0
        val steps = sequence {
            // These two Paper getters create native snapshots atomically; each is one quantum.
            // All classification, metadata inspection and reconciliation after them is sliced.
            val chunks = world.loadedChunks
            yield(Unit)
            for (chunk in chunks) {
                val key = PaperChunkKey(world.uid, chunk.x, chunk.z)
                if (!world.isChunkLoaded(key.x, key.z) || !chunk.isEntitiesLoaded) { yield(Unit); continue }
                val epoch = chunkEpochs[key] ?: 0
                val entities = chunk.entities
                yield(Unit)
                for (entity in entities) {
                    if ((chunkEpochs[key] ?: 0) != epoch || !world.isChunkLoaded(key.x, key.z)) break
                    if (entity is Item) track(entity, key, epoch, force)
                    yield(Unit)
                }
            }
        }.iterator()
        return nativeWork.submit({ !closed && (worldEpochs[world.uid] ?: 0) == worldEpoch }) {
            if (!steps.hasNext()) true else { steps.next(); false }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun merge(event: ItemMergeEvent) {
        if (protection.locked(event.entity) || protection.locked(event.target) || owner(event.entity) != null || owner(event.target) != null)
            event.isCancelled = true
    }

    private fun incident(key: String, failure: Throwable) {
        if (incidents.add(key)) Logging.error("LostEliteLoot operation={} retry=retained", key, failure)
    }
    private fun recovered(key: String) { if (incidents.remove(key)) Logging.info("LostEliteLoot recovered operation={}", key) }

    private var storageCompletion = CompletableFuture.completedFuture<Void>(null)
    fun storageDrained(): CompletableFuture<Void> = storageCompletion.copy()

    /** Host-owned ticket retirement survives consumer disable and waits for accepted local writes. */
    fun closeAsync(finalizer: () -> Unit = {}): CompletableFuture<Void> {
        completion?.let { return it.copy() }
        closed = true
        protection.starting()
        ready.completeExceptionally(CancellationException("Lost loot capture stopped"))
        tasks.close()
        val old = tracked.values.iterator()
        tracked = ConcurrentHashMap(); quick = ConcurrentHashMap()
        val storage = outbox.closeAsync(finalizer)
        storageCompletion = storage
        var releaseFailure: Throwable? = ticketFailure
        val native = try {
            nativeCleanup.submit("lost-loot-chunk-tickets") {
                if (!storage.isDone) false
                else if (old.hasNext()) {
                    try { old.next().lease?.close() } catch (failure: Throwable) {
                        if (releaseFailure == null) releaseFailure = failure else if (releaseFailure !== failure) releaseFailure!!.addSuppressed(failure)
                    }
                    false
                } else { releaseFailure?.let { throw it }; true }
            }.toCompletableFuture()
        } catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        val result = CompletableFuture.allOf(storage, native)
        completion = result
        return result.copy()
    }
    override fun close() { closeAsync() }
}
