package ru.arc.enchanting

import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import java.util.logging.Level

internal fun enchantmentFindReward(roll: Double, boxPercent: Double, keyPercent: Double): EnchantmentFindReward? {
    require(roll.isFinite() && roll in 0.0..<1.0) { "Find random value must be in [0, 1)" }
    val percent = roll * 100.0
    return when {
        percent < keyPercent -> EnchantmentFindReward.KEY
        percent < keyPercent + boxPercent -> EnchantmentFindReward.LOOTBOX
        else -> null
    }
}

internal fun validateEnchantmentFindChances(boxPercent: Double, keyPercent: Double) {
    require(boxPercent.isFinite() && keyPercent.isFinite() && boxPercent >= 0 && keyPercent >= 0 &&
        boxPercent + keyPercent <= 100.0) { "Find chances must be nonnegative and total at most 100 percent" }
}

/**
 * Main-thread owner of persistent host-bound finds and their client-only displays.
 * At most one find per source/chunk, 128 loaded scenes (256 item displays) and 128 preparations.
 * Old chunk quotas are reserved at reveal. A claim removes its durable find before spawning its
 * exact saved reward: uncertain spawn outcomes are never replayed. Native chunk/item saves retain
 * vanilla crash/backup boundaries; this is not an atomic cross-inventory crash journal.
 */
internal class EnchantmentVisibleFinds(
    private val plugin: Plugin,
    private val createReward: (EnchantmentFindReward) -> CompletableFuture<ItemStack?>,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "ae-visible-finds"),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
    private val nativeCleanup: ArcNativeCleanupService = requireNotNull(plugin.server.servicesManager.load(ArcNativeCleanupService::class.java)),
    private val serverStopping: () -> Boolean = { plugin.server.isStopping },
) : Listener, AutoCloseable {
    private data class Policy(val worlds: Set<String>, val materials: Set<Material>, val cooldownSeconds: Long)
    private data class FindKey(val world: UUID, val chunkX: Int, val chunkZ: Int, val source: EnchantmentFindSource)
    private data class Position(val world: UUID, val x: Int, val y: Int, val z: Int)
    private data class Find(
        val x: Int, val y: Int, val z: Int, val material: Material, val face: BlockFace,
        val reward: EnchantmentFindReward, val createdAt: Long,
    ) {
        fun encode() = listOf("1", x, y, z, material.name, face.name, reward.name, createdAt).joinToString("|")
        fun isAt(block: Block) = block.x == x && block.y == y && block.z == z
    }
    private data class Scene(val world: World, val find: Find, val handles: List<PacketItemDisplay>)
    private data class Preparation(val find: Find, val expectedState: String)
    private data class BreakCapture(
        val event: BlockBreakEvent, val source: EnchantmentFindSource, val find: Find?,
        val canClaim: Boolean, val afterAccepted: () -> Unit, var drop: BlockDropItemEvent? = null,
    )
    /** Detached native claim: no retired runtime, tasks, configuration, factories or global bindings. */
    private data class ClaimReceipt(
        val event: BlockBreakEvent, val drop: BlockDropItemEvent?, val canClaim: Boolean,
        val find: Find, val marker: NamespacedKey, val rewardKey: NamespacedKey, val reward: ItemStack?,
    ) {
        /** Returns true only when this exact durable find was consumed. */
        fun settle(): Boolean {
            val block = event.block
            val world = block.world
            if (event.isCancelled || !world.isChunkLoaded(block.x shr 4, block.z shr 4)) return false
            if (drop == null && block.type == find.material) return false
            val data = block.chunk.persistentDataContainer
            if (data.get(marker, PersistentDataType.STRING) != find.encode()) return false
            data.remove(marker)
            data.remove(rewardKey)
            if (canClaim && event.isDropItems && drop != null && !drop.isCancelled &&
                drop.blockState.type == find.material && reward != null) {
                // The player was eligible at the accepted break. Disconnecting during stop does not revoke it.
                // Remove before entering dropItem: callbacks may have observed an uncertain spawn; never replay.
                world.dropItem(Location(world, find.x + .5, find.y + .5, find.z + .5), reward)
            }
            return true
        }
    }

    private val policies = mutableMapOf<EnchantmentFindSource, Policy>()
    private val active = LinkedHashMap<FindKey, Scene>()
    private val preparing = mutableMapOf<FindKey, Preparation>()
    private val breaks = mutableMapOf<Position, BreakCapture>()
    private val loggedFailures = mutableSetOf<String>()
    private var attemptTick: Int? = null
    private val attemptedPlayers = mutableSetOf<UUID>()
    private var closed = false
    private var drain: CompletableFuture<Void>? = null
    @Volatile private var retiredClaims: List<ClaimReceipt> = emptyList()

    init {
        tasks.runTimer(1, 1) { reconcileSlice() }
    }

    fun register(settings: EnchantmentMiningLootSettings) {
        if (settings.survivalBackend) policies[EnchantmentFindSource.MINING] = Policy(settings.worldNames, settings.materials, 0)
    }

    fun register(settings: EnchantmentFarmingLootSettings) {
        if (settings.survivalBackend) policies[EnchantmentFindSource.FARMING] =
            Policy(settings.worldNames, settings.materials, settings.cooldownSeconds)
    }

    fun isFind(block: Block, source: EnchantmentFindSource): Boolean = guarded("read find", false) {
        loaded(block) && read(block.chunk, source)?.isAt(block) == true
    }

    fun available(block: Block, source: EnchantmentFindSource): Boolean {
        if (closed || !loaded(block) || !validHost(block, source)) return false
        val key = key(block.chunk, source)
        if (key in preparing || active.size >= MAX_ACTIVE) return false
        val data = block.chunk.persistentDataContainer
        return !data.has(marker(source)) && quotaAvailable(data, source)
    }

    /** Mining and farming share one manual discovery attempt per player per server tick. */
    fun admitRoll(player: UUID, tick: Int): Boolean {
        if (closed) return false
        if (attemptTick != tick) { attemptTick = tick; attemptedPlayers.clear() }
        return attemptedPlayers.add(player)
    }

    /** Captures provenance before eco's deferred cleanup; final native break AND drop must succeed. */
    fun captureBreak(event: BlockBreakEvent, source: EnchantmentFindSource, canClaim: Boolean, afterAccepted: () -> Unit = {}) {
        if (closed || event.isCancelled || !loaded(event.block) || breaks.size >= MAX_PENDING) return
        val position = position(event.block)
        if (position in breaks) return
        val find = read(event.block.chunk, source)?.takeIf { it.isAt(event.block) }
        val capture = BreakCapture(event, source, find, canClaim, afterAccepted)
        breaks[position] = capture
        tasks.runLater(1) {
            if (closed || breaks.remove(position) !== capture) return@runLater
            guarded("complete break", Unit) { completeBreak(capture) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onBlockDrop(event: BlockDropItemEvent) {
        val capture = breaks[position(event.block)] ?: return
        if (capture.event.player.uniqueId == event.player.uniqueId) capture.drop = event
    }

    /** No quota or world mutation occurs until the exact native reward is ready and the host is revalidated. */
    fun reveal(block: Block, source: EnchantmentFindSource, face: BlockFace, reward: EnchantmentFindReward,
               stillEligible: (Block) -> Boolean = { true }) {
        guarded("prepare find", Unit) {
            if (!available(block, source) || preparing.size >= MAX_PENDING || !stillEligible(block)) return@guarded
            val world = block.world
            val key = key(block.chunk, source)
            val now = clockMillis()
            if (now < 0) return@guarded
            val find = Find(block.x, block.y, block.z, block.type, face, reward, now)
            val preparation = Preparation(find, block.blockData.asString)
            preparing[key] = preparation
            val future = try { createReward(reward) } catch (failure: Throwable) {
                preparing.remove(key, preparation)
                throw failure
            }
            // The remote key provider also has a timeout; this bounds the consumer even if it fails to settle.
            val timeout = tasks.runLater(200) { preparing.remove(key, preparation) }
            future.whenCompleteSync(tasks) { item, failure ->
                timeout?.cancel()
                if (closed || preparing[key] !== preparation) return@whenCompleteSync
                preparing.remove(key)
                guarded("materialize find", Unit) {
                    if (failure != null) throw IllegalStateException("Reward preparation failed", failure)
                    if (item == null || item.type.isAir || item.amount != 1 ||
                        (reward == EnchantmentFindReward.LOOTBOX && !isEnchantmentLootbox(item, readTreasureKey))) return@guarded
                    if (!world.isChunkLoaded(key.chunkX, key.chunkZ)) return@guarded
                    val host = world.getBlockAt(find.x, find.y, find.z)
                    if (host.blockData.asString != preparation.expectedState || !available(host, source) || !stillEligible(host)) return@guarded
                    val bytes = item.serializeAsBytes()
                    check(bytes.size in 1..MAX_REWARD_BYTES) { "Find reward exceeds its chunk payload limit" }
                    // All final checks, reservation and marker writes share the owning thread.
                    if (closed || !available(host, source) || host.blockData.asString != preparation.expectedState) return@guarded
                    val data = host.chunk.persistentDataContainer
                    reserveQuota(data, source)
                    data.set(rewardKey(source), PersistentDataType.BYTE_ARRAY, bytes)
                    data.set(marker(source), PersistentDataType.STRING, find.encode())
                    show(host.chunk, source, find, item)
                }
            }
        }
    }

    private fun completeBreak(capture: BreakCapture) {
        val event = capture.event
        val block = event.block
        if (event.isCancelled || !loaded(block)) return
        val drop = capture.drop
        val player = event.player
        val accepted = capture.canClaim && event.isDropItems && drop != null && !drop.isCancelled &&
            player.isOnline && player.gameMode == GameMode.SURVIVAL && player.world.uid == block.world.uid
        val find = capture.find
        if (find != null) {
            if (detachClaim(capture)?.settle() == true) active.remove(key(block.chunk, capture.source))?.handles?.forEach { it.remove() }
        } else if (accepted) capture.afterAccepted()
    }

    private fun detachClaim(capture: BreakCapture): ClaimReceipt? {
        val find = capture.find ?: return null
        val block = capture.event.block
        if (!loaded(block) || read(block.chunk, capture.source) != find) return null
        val bytes = block.chunk.persistentDataContainer.get(rewardKey(capture.source), PersistentDataType.BYTE_ARRAY)
        val reward = bytes?.takeIf { it.size in 1..MAX_REWARD_BYTES }?.let { ItemStack.deserializeBytes(it) }?.takeIf {
            !it.type.isAir && it.amount == 1 && (find.reward != EnchantmentFindReward.LOOTBOX || isEnchantmentLootbox(it, readTreasureKey))
        }
        return ClaimReceipt(capture.event, capture.drop, capture.canClaim, find, marker(capture.source), rewardKey(capture.source), reward)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) = restore(event.chunk)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        for (source in policies.keys) active.remove(key(event.chunk, source))?.handles?.forEach { it.remove() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        if (event.from.blockX == to.blockX && event.from.blockZ == to.blockZ && event.from.world == to.world) return
        restoreAt(to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = restoreAt(event.player.location)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        tasks.runLater(1) { if (!closed && !event.isCancelled) restoreAt(event.player.location) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (closed || policies.keys.none { tracked(event.blockPlaced, it) }) return
        tasks.runLater(1) {
            if (!closed && !event.isCancelled && event.canBuild()) invalidate(event.blockPlaced)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val blocks = event.blocks.take(MAX_PISTON_BLOCKS).filter { block -> policies.keys.any { tracked(block, it) } }
        if (closed || blocks.isEmpty()) return
        tasks.runLater(1) { if (!closed && !event.isCancelled) blocks.forEach(::invalidate) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val blocks = event.blocks.take(MAX_PISTON_BLOCKS).filter { block -> policies.keys.any { tracked(block, it) } }
        if (closed || blocks.isEmpty()) return
        tasks.runLater(1) { if (!closed && !event.isCancelled) blocks.forEach(::invalidate) }
    }

    private fun invalidate(block: Block) = guarded("invalidate host", Unit) {
        if (!loaded(block)) return@guarded
        for (source in policies.keys) {
            val key = key(block.chunk, source)
            if (preparing[key]?.find?.isAt(block) == true) preparing.remove(key)
            if (read(block.chunk, source)?.isAt(block) == true) discard(block.chunk, source)
        }
    }

    private fun tracked(block: Block, source: EnchantmentFindSource) =
        loaded(block) && (isFind(block, source) || preparing[key(block.chunk, source)]?.find?.isAt(block) == true)

    private fun restoreAt(location: Location) {
        val world = location.world ?: return
        if (!closed && world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) restore(location.chunk)
    }

    private fun restore(chunk: Chunk) = guarded("restore find", Unit) {
        if (closed) return@guarded
        for (source in policies.keys) {
            if (!allowsWorld(chunk.world, source)) continue
            val key = key(chunk, source)
            if (key in active || active.size >= MAX_ACTIVE) continue
            val find = read(chunk, source) ?: continue
            val block = chunk.world.getBlockAt(find.x, find.y, find.z)
            if (!matches(block, source, find)) { discard(chunk, source); continue }
            val bytes = chunk.persistentDataContainer.get(rewardKey(source), PersistentDataType.BYTE_ARRAY) ?: continue
            if (bytes.size !in 1..MAX_REWARD_BYTES) continue
            val item = ItemStack.deserializeBytes(bytes)
            if (item.type.isAir || item.amount != 1) continue
            show(chunk, source, find, item)
        }
    }

    private fun show(chunk: Chunk, source: EnchantmentFindSource, find: Find, reward: ItemStack) {
        val key = key(chunk, source)
        if (closed || key in active || active.size >= MAX_ACTIVE) return
        val host = chunk.world.getBlockAt(find.x, find.y, find.z)
        val anchor = Location(chunk.world, find.x + .5, find.y + .5, find.z + .5)
        val poses = if (source == EnchantmentFindSource.MINING) EnchantmentFindGeometry.mining(find.face, find.reward) else {
            val top = EnchantmentFindGeometry.cropVisualTop(host.type) ?: run {
                // Wall-attached cocoa follows its native, facing-dependent centre and outline.
                val bounds = host.boundingBox
                anchor.x = bounds.centerX
                anchor.z = bounds.centerZ
                (bounds.maxY - host.y).toFloat().coerceIn(0f, 1f)
            }
            EnchantmentFindGeometry.farming(top, find.reward, find.face)
        }
        val visual = if (find.reward == EnchantmentFindReward.LOOTBOX) ItemStack(Material.CHEST) else reward
        val created = mutableListOf<PacketItemDisplay>()
        try {
            for (pose in poses) {
                val display = displays.spawnItem(anchor.clone().add(pose.offset.x.toDouble(), pose.offset.y.toDouble(), pose.offset.z.toDouble()), visual)
                created += display
                display.apply {
                    itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    billboard = Display.Billboard.FIXED
                    brightness = Display.Brightness(12, 12)
                    viewRange = .5f
                    displayWidth = 1f
                    displayHeight = 1f
                    shadowRadius = 0f
                    shadowStrength = 0f
                    interpolationDuration = 0
                    teleportDuration = 0
                    transformation = Transformation(Vector3f(), pose.rotation, Vector3f(pose.scale), Quaternionf(0f, 1f, 0f, 0f))
                }
            }
            active[key] = Scene(chunk.world, find, created.toList())
        } catch (failure: Throwable) {
            created.forEach { it.remove() }
            throw failure
        }
    }

    /** Eight host checks per tick catch explosions, water, growth resets and edits outside Bukkit's ordinary events. */
    private fun reconcileSlice() {
        if (closed) return
        repeat(minOf(HOST_CHECKS_PER_TICK, active.size)) {
            val entry = active.entries.firstOrNull() ?: return
            val key = entry.key
            val scene = entry.value
            active.remove(key)
            guarded("reconcile host", Unit) {
                if (!scene.world.isChunkLoaded(key.chunkX, key.chunkZ)) return@guarded
                val chunk = scene.world.getChunkAt(key.chunkX, key.chunkZ)
                val host = scene.world.getBlockAt(scene.find.x, scene.find.y, scene.find.z)
                if (position(host) in breaks) { active[key] = scene; return@guarded }
                if (read(chunk, key.source) == scene.find && matches(host, key.source, scene.find)) active[key] = scene
                else {
                    if (read(chunk, key.source) == scene.find) discard(chunk, key.source)
                }
            }
            if (key !in active) scene.handles.forEach { it.remove() }
        }
    }

    private fun matches(block: Block, source: EnchantmentFindSource, find: Find) =
        block.type == find.material && validHost(block, source)

    private fun validHost(block: Block, source: EnchantmentFindSource): Boolean {
        val policy = policies[source] ?: return false
        if (!allowsWorld(block.world, source) || block.type !in policy.materials) return false
        if (source == EnchantmentFindSource.MINING) return true
        val crop = block.blockData as? Ageable ?: return false
        return crop.age == crop.maximumAge
    }

    private fun allowsWorld(world: World, source: EnchantmentFindSource) =
        world.name.lowercase(Locale.ROOT) in policies[source]?.worlds.orEmpty()

    private fun quotaAvailable(data: PersistentDataContainer, source: EnchantmentFindSource): Boolean {
        if (source == EnchantmentFindSource.MINING) return !data.has(enchantmentMiningChunkClaimKey)
        val now = clockMillis()
        if (now < 0) return false
        if (!data.has(enchantmentFarmingChunkClaimKey)) return true
        val claimed = data.get(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG) ?: return false
        return claimed >= 0 && claimed <= now && now - claimed >= (policies[source]?.cooldownSeconds ?: Long.MAX_VALUE / 1_000) * 1_000L
    }

    private fun reserveQuota(data: PersistentDataContainer, source: EnchantmentFindSource) {
        if (source == EnchantmentFindSource.MINING) data.set(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE, 1.toByte())
        else data.set(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG, clockMillis())
    }

    private fun read(chunk: Chunk, source: EnchantmentFindSource): Find? {
        if (!allowsWorld(chunk.world, source)) return null
        val raw = chunk.persistentDataContainer.get(marker(source), PersistentDataType.STRING) ?: return null
        if (raw.length > 192) return null
        val parts = raw.split('|')
        if (parts.size != 8 || parts[0] != "1") return null
        return runCatching {
            Find(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), Material.valueOf(parts[4]),
                BlockFace.valueOf(parts[5]), EnchantmentFindReward.valueOf(parts[6]), parts[7].toLong()).takeIf {
                it.x shr 4 == chunk.x && it.z shr 4 == chunk.z && it.y in chunk.world.minHeight..<chunk.world.maxHeight &&
                    it.createdAt >= 0 && it.material in policies[source]!!.materials && it.face in FACES
            }
        }.getOrNull()
    }

    private fun discard(chunk: Chunk, source: EnchantmentFindSource) {
        active.remove(key(chunk, source))?.handles?.forEach { it.remove() }
        chunk.persistentDataContainer.remove(marker(source))
        chunk.persistentDataContainer.remove(rewardKey(source))
    }

    private fun loaded(block: Block) = block.world.isChunkLoaded(block.x shr 4, block.z shr 4)
    private fun key(chunk: Chunk, source: EnchantmentFindSource) = FindKey(chunk.world.uid, chunk.x, chunk.z, source)
    private fun position(block: Block) = Position(block.world.uid, block.x, block.y, block.z)

    private inline fun <T> guarded(reason: String, fallback: T, action: () -> T): T = try { action() }
    catch (failure: Exception) { report(reason, failure); fallback }
    catch (failure: LinkageError) { report(reason, failure); fallback }

    private fun report(reason: String, failure: Throwable) {
        if (loggedFailures.add(reason)) plugin.logger.log(Level.WARNING,
            "AE visible find failed at $reason; further failures of this kind are suppressed until reload", failure)
    }

    fun closeAsync(): CompletableFuture<Void> {
        drain?.let {
            if (serverStopping()) flushRetiredClaimsForServerStop()
            return it.copy()
        }
        val result = CompletableFuture<Void>()
        drain = result
        closed = true
        val claims = breaks.values.mapNotNull { guarded("detach claim", null) { detachClaim(it) } }
        retiredClaims = claims
        tasks.close()
        preparing.clear()
        breaks.clear()
        attemptedPlayers.clear()
        active.clear()
        // ArcCore's cursor survives Tasks.reset and consumer hotload. It never executes inside the
        // triggering native drop dispatch, so later event cancellation remains authoritative.
        // Full server stop has no next tick; its event loop has already stopped accepting breaks.
        val settlement = settleClaims(claims, nativeCleanup, serverStopping())
        settlement.whenComplete { _, failure -> if (failure == null) retiredClaims = emptyList() }
        val visuals = try { displays.closeAsync() } catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        CompletableFuture.allOf(settlement, visuals).whenComplete { _, failure ->
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        }
        return result.copy()
    }

    /** A server stop can overtake an earlier module reload before ArcCore receives its next tick. */
    fun flushRetiredClaimsForServerStop() {
        check(serverStopping()) { "Synchronous retired-claim settlement is only valid after the server starts stopping" }
        // The old queued cursor may still run or fail later. Exact durable-marker removal makes either safe.
        settleClaims(retiredClaims, nativeCleanup, stopping = true).join() // Already completed synchronously; never waits.
        retiredClaims = emptyList()
    }

    override fun close() { closeAsync() }

    private companion object {
        fun settleClaims(claims: List<ClaimReceipt>, cleanup: ArcNativeCleanupService, stopping: Boolean): CompletableFuture<Void> {
            if (claims.isEmpty()) return CompletableFuture.completedFuture(null)
            val remaining = claims.iterator()
            var firstFailure: Throwable? = null
            val step = BooleanSupplier {
                if (remaining.hasNext()) try { remaining.next().settle() } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure else if (firstFailure !== failure) firstFailure!!.addSuppressed(failure)
                }
                if (remaining.hasNext()) false else {
                    firstFailure?.let { throw it }
                    true
                }
            }
            return try {
                if (stopping) {
                    while (!step.asBoolean) { /* At most MAX_PENDING native receipts; shutdown cannot promise another tick. */ }
                    CompletableFuture.completedFuture(null)
                } else cleanup.submit("ARC:ae-find-claims", step).toCompletableFuture()
            } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        }

        const val MAX_ACTIVE = 128
        const val MAX_PENDING = 128
        const val MAX_REWARD_BYTES = 65_536
        const val HOST_CHECKS_PER_TICK = 8
        const val MAX_PISTON_BLOCKS = 64 // Native pistons move at most twelve; also bound synthetic events.
        val FACES = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
        fun marker(source: EnchantmentFindSource) = NamespacedKey("arc", "ae_${source.name.lowercase(Locale.ROOT)}_find_v1")
        fun rewardKey(source: EnchantmentFindSource) = NamespacedKey("arc", "ae_${source.name.lowercase(Locale.ROOT)}_find_item_v1")
    }
}
