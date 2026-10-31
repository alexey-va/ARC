package ru.arc.enchanting

import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.itemcatalog.personalTreasureMapLocationUnclaimed
import ru.arc.ops.ItemPresets
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs

internal data class EnchantmentWorldCacheSettings(
    val survivalBackend: Boolean,
    val enabled: Boolean,
    val worldNames: Set<String>,
    val chanceDenominator: Int,
) {
    init {
        require(chanceDenominator > 0) { "World-cache chance denominator must be positive" }
        require(worldNames.none(String::isBlank)) { "World-cache world names must not be blank" }
        require(worldNames.map { it.trim().lowercase(Locale.ROOT) }.distinct().size == worldNames.size) {
            "World-cache world names must be unique ignoring case"
        }
    }
}

internal data class EnchantmentWorldCacheColumn(val x: Int, val z: Int)

/** Stable world/chunk selection, including negative coordinates; neither player nor visit count is a seed. */
internal fun enchantmentWorldCacheColumn(
    worldId: UUID,
    chunkX: Int,
    chunkZ: Int,
    chanceDenominator: Int,
): EnchantmentWorldCacheColumn? {
    require(chanceDenominator > 0)
    // Stable feature salt: changing it would relocate uninspected candidates, so never use a startup random seed.
    val seed = mixWorldCacheHash(
        worldId.mostSignificantBits xor java.lang.Long.rotateLeft(worldId.leastSignificantBits, 21) xor
            (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL) xor 0x41455f4341434845L,
    )
    if (java.lang.Long.remainderUnsigned(seed, chanceDenominator.toLong()) != 0L) return null
    val position = mixWorldCacheHash(seed xor 0x5355524641434531L)
    val localX = 2 + java.lang.Long.remainderUnsigned(position, 12L).toInt()
    val localZ = 2 + java.lang.Long.remainderUnsigned(java.lang.Long.rotateLeft(position, 32), 12L).toInt()
    return EnchantmentWorldCacheColumn((chunkX shl 4) + localX, (chunkZ shl 4) + localZ)
}

private fun mixWorldCacheHash(value: Long): Long {
    var mixed = (value xor (value ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
}

internal fun isEnchantmentWorldCacheGround(material: Material): Boolean = material in WORLD_CACHE_GROUND

internal fun singleEnchantmentWorldCacheReward(
    stacks: List<ItemStack>,
    readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
): ItemStack? {
    val stack = stacks.singleOrNull()?.takeIf { isEnchantmentLootbox(it, readTreasureKey) } ?: return null
    return stack.clone()
}

/**
 * Owns at most 64 pending loaded-chunk probes and performs one probe per tick, on Paper's main thread.
 * The caller registers this listener and closes this generation before replacing its settings.
 *
 * A permanent chunk PDC marker is written before the native chest mutation. The chest and marker use
 * the same chunk save; an empty, broken or missing chest is never refilled, recreated or deleted here.
 * This deliberately retains vanilla container/inventory/HuskSync crash and backup-restore boundaries:
 * a chunk save is not an atomic transaction with a player's inventory or cross-server synchronization.
 */
internal class EnchantmentWorldCaches(
    settings: EnchantmentWorldCacheSettings,
    private val plugin: Plugin = ARC.instance,
    private val isNaturalBlock: (Block) -> Boolean = { false },
    private val isUnclaimed: (Location) -> Boolean? = { personalTreasureMapLocationUnclaimed(plugin, it) },
    private val resolveReward: () -> List<ItemStack> = { ItemPresets.resolveStacks("ae_lootbox", 1).getOrThrow() },
    scheduler: TaskScheduler = Tasks.scheduler,
    private val createChestState: (Location) -> Chest = { location ->
        Material.CHEST.createBlockData().createBlockState().copy(location) as Chest
    },
    private val readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
) : Listener, AutoCloseable {
    private data class ChunkKey(val worldId: UUID, val x: Int, val z: Int)
    private data class Probe(val playerId: UUID, val column: EnchantmentWorldCacheColumn)

    private val settings = settings.copy(worldNames = Collections.unmodifiableSet(settings.worldNames.toSet()))
    private val worlds = settings.worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
    private val inspectedKey = NamespacedKey(plugin, "ae_world_cache_inspected")
    private val pending = LinkedHashMap<ChunkKey, Probe>()
    private val tasks = LifecycleTaskScope(scheduler)
    private val token = tasks.token()
    private var scheduled = false
    private var closed = false
    private var failureReported = false

    internal fun settingsSnapshot(): EnchantmentWorldCacheSettings =
        settings.copy(worldNames = Collections.unmodifiableSet(settings.worldNames.toSet()))

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (closed || !settings.enabled || !settings.survivalBackend || event.isCancelled) return
        val to = event.to ?: return
        val from = event.from
        val world = to.world ?: return
        val chunkX = to.blockX shr 4
        val chunkZ = to.blockZ shr 4
        if (from.world?.uid == world.uid && (from.blockX shr 4) == chunkX && (from.blockZ shr 4) == chunkZ) return
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL || !allowsWorld(world) || !player.isOnline || player.isDead) return
        val key = ChunkKey(world.uid, chunkX, chunkZ)
        if (key in pending || pending.size >= MAX_PENDING) return
        val column = enchantmentWorldCacheColumn(world.uid, chunkX, chunkZ, settings.chanceDenominator) ?: return
        pending[key] = Probe(player.uniqueId, column)
        scheduleNext()
    }

    private fun scheduleNext() {
        if (closed || scheduled || pending.isEmpty()) return
        scheduled = true
        tasks.runLater(token, 1) {
            scheduled = false
            val iterator = pending.entries.iterator()
            if (!iterator.hasNext()) return@runLater
            val (key, probe) = iterator.next()
            iterator.remove()
            try {
                inspect(key, probe)
            } catch (failure: Exception) {
                reportFailure(key, failure)
            } catch (failure: LinkageError) {
                reportFailure(key, failure)
            } finally {
                scheduleNext()
            }
        }
    }

    private fun inspect(key: ChunkKey, probe: Probe) {
        if (closed) return
        val world = plugin.server.getWorld(key.worldId) ?: return
        val player = plugin.server.getPlayer(probe.playerId) ?: return
        if (!playerAtChunk(player, key) || !allowsWorld(world) || !world.isChunkLoaded(key.x, key.z)) return
        val chunk = world.getChunkAt(key.x, key.z)
        // Any existing type/value is authoritative, including unknown future versions: never repair it by rerolling.
        if (chunk.persistentDataContainer.has(inspectedKey)) return
        val (x, z) = probe.column
        // WORLD_SURFACE includes leaves, plants, fluids and player roofs. No downward search under obstructions.
        val groundY = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
        if (abs(player.location.y - (groundY + 1.0)) > MAX_HEIGHT_DIFFERENCE) return

        // Underground visits and unprocessed queue overflow must not consume a later surface exploration attempt.
        chunk.persistentDataContainer.set(inspectedKey, PersistentDataType.BYTE, 1.toByte())
        if (!safeColumn(world, key, probe.column, groundY)) return
        val reward = singleEnchantmentWorldCacheReward(resolveReward(), readTreasureKey)
            ?: error("ae_lootbox must resolve to exactly one nonempty physical AE box")
        val location = Location(world, x.toDouble(), groundY + 1.0, z.toDouble())
        // Prepare the complete snapshot before touching AIR: no empty chest for unavailable/invalid rewards.
        val chest = createChestState(location)
        check(chest.type == Material.CHEST && chest.isPlaced && chest.location == location) { "Invalid world-cache chest snapshot" }
        chest.lootTable = null
        chest.customName(Component.text("Таинственный тайник чар"))
        val inventory = chest.snapshotInventory
        check(inventory.size == 27 && inventory.contents.all { it == null || it.type.isAir }) { "World-cache snapshot must be a new empty single chest" }
        inventory.setItem(13, reward)
        check(inventory.contents.filterNotNull().filterNot { it.type.isAir } == listOf(reward)) { "World-cache snapshot rejected its reward" }

        // All native reads and this write share one synchronous step; callbacks cannot leave a stale placement valid.
        if (closed || !playerAtChunk(player, key) || abs(player.location.y - location.y) > MAX_HEIGHT_DIFFERENCE ||
            !safeColumn(world, key, probe.column, groundY)
        ) return
        if (closed || world.getBlockAt(x, groundY + 1, z).type != Material.AIR) return
        check(chest.update(true, false)) { "Could not apply world-cache chest snapshot" }
    }

    private fun safeColumn(world: World, key: ChunkKey, column: EnchantmentWorldCacheColumn, groundY: Int): Boolean {
        if (!world.isChunkLoaded(key.x, key.z) || groundY < world.minHeight || groundY + 2 >= world.maxHeight) return false
        val (x, z) = column
        val ground = world.getBlockAt(x, groundY, z)
        if (!isNaturalBlock(ground)) return false
        for (y in groundY..groundY + 2) {
            if (isUnclaimed(Location(world, x.toDouble(), y.toDouble(), z.toDouble())) != true) return false
        }
        // Read physical occupancy after provider calls, so even a reentrant callback cannot authorize overwriting a block.
        if (!world.isChunkLoaded(key.x, key.z) || !isEnchantmentWorldCacheGround(ground.type)) return false
        val target = world.getBlockAt(x, groundY + 1, z)
        if (target.type != Material.AIR || world.getBlockAt(x, groundY + 2, z).type != Material.AIR) return false
        if (!world.worldBorder.isInside(Location(world, x + 0.5, groundY + 1.0, z + 0.5))) return false
        // The 2..13 inset keeps this fixed 3x3x3 neighbourhood entirely inside the already loaded chunk.
        for (dx in -1..1) for (dz in -1..1) for (dy in 0..2) {
            if (dx == 0 && dz == 0) continue
            if (world.getBlockAt(x + dx, groundY + dy, z + dz).type in WORLD_CACHE_CONTAINERS) return false
        }
        return true
    }

    private fun playerAtChunk(player: Player, key: ChunkKey): Boolean {
        if (!player.isOnline || player.isDead || player.gameMode != GameMode.SURVIVAL) return false
        val location = player.location
        return location.world?.uid == key.worldId && (location.blockX shr 4) == key.x && (location.blockZ shr 4) == key.z
    }

    private fun allowsWorld(world: World): Boolean = world.name.lowercase(Locale.ROOT) in worlds

    private fun reportFailure(key: ChunkKey, failure: Throwable) {
        if (failureReported) return
        failureReported = true
        plugin.logger.log(Level.WARNING, "AE world-cache probe failed at ${key.worldId}/${key.x}/${key.z}; existing inspected markers will not be retried", failure)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        pending.clear()
        scheduled = false
        HandlerList.unregisterAll(this)
    }

    private companion object {
        const val MAX_PENDING = 64
        const val MAX_HEIGHT_DIFFERENCE = 12.0
    }
}

private val WORLD_CACHE_GROUND = setOf(
    Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL,
    Material.MYCELIUM, Material.MOSS_BLOCK, Material.STONE, Material.DEEPSLATE, Material.ANDESITE,
    Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CALCITE, Material.SAND, Material.RED_SAND,
    Material.GRAVEL, Material.CLAY, Material.SNOW_BLOCK, Material.TERRACOTTA,
)

private val WORLD_CACHE_CONTAINERS = setOf(
    Material.CHEST, Material.BARREL, Material.HOPPER, Material.DROPPER, Material.DISPENSER,
    Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND,
    Material.CRAFTER, Material.CHISELED_BOOKSHELF, Material.DECORATED_POT,
) + Material.entries.filter { it.name.endsWith("_CHEST") || it.name.endsWith("SHULKER_BOX") || it.name.endsWith("_SHELF") }
