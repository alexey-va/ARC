package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.ops.ItemPresets
import ru.arc.util.Logging
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** A validated, detached generation; configuration and reward balance belong to the caller. */
internal class EnchantmentFarmingLootSettings(
    val survivalBackend: Boolean,
    worldNames: Set<String>,
    materials: Set<Material>,
    val chancePercent: Double,
    val cooldownSeconds: Long,
) {
    val worldNames: Set<String> = Collections.unmodifiableSet(worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) })
    val materials: Set<Material> = Collections.unmodifiableSet(materials.toSet())

    init {
        require(worldNames.none(String::isBlank)) { "Farming loot world names must not be blank" }
        require(this.worldNames.size == worldNames.size) { "Farming loot world names must be unique ignoring case" }
        require(materials.all { it.isBlock && !it.isAir }) { "Farming loot materials must be non-air blocks" }
        require(chancePercent.isFinite() && chancePercent in 0.0..100.0) { "Farming loot chance must be between 0 and 100" }
        require(cooldownSeconds in 1L..31_536_000L) { "Farming loot cooldown must be between 1 and 31536000 seconds" }
    }

    fun allowsWorld(worldName: String): Boolean =
        survivalBackend && worldName.lowercase(Locale.ROOT) in worldNames
}

internal val enchantmentFarmingChunkClaimKey = NamespacedKey("arc", "ae_farming_lootbox_at_v1")

/**
 * One physical ae_lootbox per chunk cooldown, shared by all players and generations.
 * Runs only on Paper's owning thread; native crop drops and experience are unchanged.
 *
 * Chunk PDC survives ordinary saves/reloads, but its save and the item's save are
 * not atomic on a crash. This local anti-replay guard is not an item/chunk journal.
 */
internal class EnchantmentFarmingLoot(
    private val settings: EnchantmentFarmingLootSettings,
    private val createLootbox: () -> ItemStack? = {
        ItemPresets.resolveStacks("ae_lootbox", 1).getOrThrow().singleOrNull()
    },
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val currentTick: () -> Int = Bukkit::getCurrentTick,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val afterEvent: (() -> Unit) -> Unit = { task -> tasks.runLater(1) { task() }; Unit },
    private val readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
) : Listener, AutoCloseable {
    private var closed = false
    private var attemptTick: Int? = null
    // Cleared on the next eligible tick: bounded by players attempting a break in one tick.
    private val attemptedPlayers = mutableSetOf<UUID>()
    private val loggedFailures = mutableSetOf<String>()

    internal fun settingsSnapshot(): EnchantmentFarmingLootSettings = settings

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (closed || event.isCancelled || !event.isDropItems || !settings.survivalBackend || settings.chancePercent == 0.0) return
        val player = event.player
        val block = event.block
        val world = block.world
        if (!player.isOnline || player.gameMode != GameMode.SURVIVAL || player.world.uid != world.uid ||
            !settings.allowsWorld(world.name) || block.type !in settings.materials
        ) return
        val crop = block.blockData as? Ageable ?: return
        if (crop.age != crop.maximumAge) return

        val tick = currentTick()
        if (attemptTick != tick) {
            attemptTick = tick
            attemptedPlayers.clear()
        }
        if (!attemptedPlayers.add(player.uniqueId)) return
        try {
            val roll = nextDouble()
            check(roll.isFinite() && roll in 0.0..<1.0) { "Farming loot random value must be in [0, 1)" }
            if (roll * 100.0 >= settings.chancePercent) return
            val chunkX = block.x shr 4
            val chunkZ = block.z shr 4
            if (!world.isChunkLoaded(chunkX, chunkZ)) return
            if (!cooldownExpired(block.chunk.persistentDataContainer, clockMillis())) return

            val x = block.x
            val y = block.y
            val z = block.z
            val worldId = world.uid
            val location = Location(world, x + 0.5, y + 0.5, z + 0.5)
            afterEvent {
                try {
                    if (closed || event.isCancelled || !event.isDropItems || !player.isOnline || player.gameMode != GameMode.SURVIVAL ||
                        player.world.uid != worldId || !world.isChunkLoaded(chunkX, chunkZ)
                    ) return@afterEvent
                    // Do not inspect the old block until its chunk is known to be loaded.
                    if (!world.getBlockAt(x, y, z).type.isAir) return@afterEvent
                    deliverLootbox(location)
                } catch (failure: Exception) {
                    reportFailure("break completion", location, failure)
                } catch (failure: LinkageError) {
                    reportFailure("break completion", location, failure)
                }
            }
        } catch (failure: Exception) {
            reportFailure("break admission", block.location, failure)
        } catch (failure: LinkageError) {
            reportFailure("break admission", block.location, failure)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        attemptedPlayers.clear()
        tasks.close()
    }

    private fun cooldownExpired(data: PersistentDataContainer, now: Long): Boolean {
        if (now < 0) return false
        if (!data.has(enchantmentFarmingChunkClaimKey)) return true
        // Unknown marker types, corrupt timestamps and clock rollback deny issuance.
        val claimedAt = data.get(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG) ?: return false
        return claimedAt >= 0 && claimedAt <= now && now - claimedAt >= settings.cooldownSeconds * 1_000L
    }

    private fun deliverLootbox(location: Location) {
        val world = requireNotNull(location.world)
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        if (closed || !world.isChunkLoaded(chunkX, chunkZ)) return
        // This synchronous section never loads a chunk: the owning thread checked it above.
        val data = world.getChunkAt(chunkX, chunkZ).persistentDataContainer
        if (!cooldownExpired(data, clockMillis())) return
        try {
            val reward = checkNotNull(createLootbox()) { "ItemPresets ae_lootbox did not resolve to one stack" }
            check(isEnchantmentLootbox(reward, readTreasureKey)) {
                "ItemPresets ae_lootbox must contain exactly one item with the AE treasure key"
            }
            val now = clockMillis()
            if (closed || !world.isChunkLoaded(chunkX, chunkZ) || !cooldownExpired(data, now)) return
            val previousClaim = data.get(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG)

            // Reserve before spawn to block reentrant attempts and uncertain retries.
            data.set(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG, now)
            var candidate: Item? = null
            try {
                val dropped = world.dropItem(location, reward.clone()) { candidate = it }
                if (!dropped.isValid || dropped.isDead) {
                    // A normally returned, rejected spawn has no physical item to replay.
                    if (previousClaim == null) data.remove(enchantmentFarmingChunkClaimKey)
                    else data.set(enchantmentFarmingChunkClaimKey, PersistentDataType.LONG, previousClaim)
                    return
                }
                check(candidate === dropped) { "Farming loot spawn did not expose its candidate before admission" }
            } catch (failure: Exception) {
                // An exception once spawn starts has an uncertain outcome; retain the
                // timestamp even if a candidate may already have been observed.
                reportFailure("spawn outcome uncertain; chunk remains reserved", location, failure)
            } catch (failure: LinkageError) {
                reportFailure("spawn outcome uncertain; chunk remains reserved", location, failure)
            }
        } catch (failure: Exception) {
            reportFailure("lootbox creation or reservation", location, failure)
        } catch (failure: LinkageError) {
            reportFailure("lootbox creation or reservation", location, failure)
        }
    }

    private fun reportFailure(reason: String, location: Location, failure: Throwable) {
        if (!loggedFailures.add(reason)) return
        Logging.error(
            "AE farming loot failed reason={} world={} block={},{},{}; further failures of this kind are suppressed until reload",
            reason, location.world?.name, location.blockX, location.blockY, location.blockZ, failure,
        )
    }
}
