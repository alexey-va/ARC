package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.ops.ItemPresets
import ru.arc.util.Logging
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** A validated, detached generation; configuration and reward balance belong to the caller. */
internal class EnchantmentMiningLootSettings(
    val survivalBackend: Boolean,
    worldNames: Set<String>,
    materials: Set<Material>,
    val chancePercent: Double,
) {
    val worldNames: Set<String> = Collections.unmodifiableSet(worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) })
    val materials: Set<Material> = Collections.unmodifiableSet(materials.toSet())

    init {
        require(worldNames.none(String::isBlank)) { "Mining loot world names must not be blank" }
        require(this.worldNames.size == worldNames.size) { "Mining loot world names must be unique ignoring case" }
        require(materials.all { it.isBlock && !it.isAir }) { "Mining loot materials must be non-air blocks" }
        require(chancePercent.isFinite() && chancePercent in 0.0..100.0) { "Mining loot chance must be between 0 and 100" }
    }

    fun allowsWorld(worldName: String): Boolean =
        survivalBackend && worldName.lowercase(Locale.ROOT) in worldNames
}

internal val enchantmentMiningChunkClaimKey = NamespacedKey("arc", "ae_mining_lootbox_claim_v1")

/**
 * One physical ae_lootbox per chunk. Runs only on Paper's owning thread.
 *
 * Provenance is captured before eco's deferred break cleanup; absence or failure
 * of the injected provider denies a reward. Native block drops are never changed.
 * Chunk PDC is a local anti-replay guard, not an atomic item/chunk crash journal.
 */
internal class EnchantmentMiningLoot(
    private val settings: EnchantmentMiningLootSettings,
    private val isNaturalBlock: (Block) -> Boolean = { false },
    private val createLootbox: () -> ItemStack? = {
        ItemPresets.resolveStacks("ae_lootbox", 1).getOrThrow().singleOrNull()
    },
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val currentTick: () -> Int = Bukkit::getCurrentTick,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val afterEvent: (() -> Unit) -> Unit = { task -> tasks.runLater(1) { task() }; Unit },
    private val readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
) : Listener, AutoCloseable {
    private var closed = false
    private var attemptTick: Int? = null
    // Cleared on the next eligible tick: bounded by players attempting a break in one tick.
    private val attemptedPlayers = mutableSetOf<UUID>()
    private val loggedFailures = mutableSetOf<String>()

    internal fun settingsSnapshot(): EnchantmentMiningLootSettings = settings

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (closed || event.isCancelled || !event.isDropItems || !settings.survivalBackend || settings.chancePercent == 0.0) return
        val player = event.player
        val block = event.block
        val world = block.world
        if (!player.isOnline || player.gameMode != GameMode.SURVIVAL || player.world.uid != world.uid ||
            !settings.allowsWorld(world.name) || block.type !in settings.materials ||
            !Tag.ITEMS_PICKAXES.isTagged(player.inventory.itemInMainHand.type)
        ) return

        val tick = currentTick()
        if (attemptTick != tick) {
            attemptTick = tick
            attemptedPlayers.clear()
        }
        if (!attemptedPlayers.add(player.uniqueId)) return
        try {
            val roll = nextDouble()
            check(roll.isFinite() && roll in 0.0..<1.0) { "Mining loot random value must be in [0, 1)" }
            if (roll * 100.0 >= settings.chancePercent) return
            val chunkX = block.x shr 4
            val chunkZ = block.z shr 4
            if (!world.isChunkLoaded(chunkX, chunkZ)) return
            if (block.chunk.persistentDataContainer.has(enchantmentMiningChunkClaimKey)) return
            if (!isNaturalBlock(block)) return

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

    private fun deliverLootbox(location: Location) {
        val world = requireNotNull(location.world)
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        if (closed || !world.isChunkLoaded(chunkX, chunkZ)) return
        // This synchronous section never loads a chunk: the owning thread checked it above.
        val data = world.getChunkAt(chunkX, chunkZ).persistentDataContainer
        if (data.has(enchantmentMiningChunkClaimKey)) return
        try {
            val reward = checkNotNull(createLootbox()) { "ItemPresets ae_lootbox did not resolve to one stack" }
            check(isEnchantmentLootbox(reward, readTreasureKey)) {
                "ItemPresets ae_lootbox must contain exactly one item with the AE treasure key"
            }
            if (closed || !world.isChunkLoaded(chunkX, chunkZ) || data.has(enchantmentMiningChunkClaimKey)) return

            // PENDING blocks reentrant attempts and uncertain retries without claiming delivery.
            data.set(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE, CLAIM_PENDING)
            var candidate: Item? = null
            try {
                val dropped = world.dropItem(location, reward.clone()) { candidate = it }
                if (!dropped.isValid || dropped.isDead) {
                    // A normally returned, rejected spawn has no physical item to replay.
                    data.remove(enchantmentMiningChunkClaimKey)
                    return
                }
                check(candidate === dropped) { "Mining loot spawn did not expose its candidate before admission" }
                data.set(enchantmentMiningChunkClaimKey, PersistentDataType.BYTE, CLAIM_DELIVERED)
            } catch (failure: Exception) {
                // Once spawn starts, an exception has an uncertain outcome. Keep PENDING;
                // even a valid candidate may already have been observed by another plugin.
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
            "AE mining loot failed reason={} world={} block={},{},{}; further failures of this kind are suppressed until reload",
            reason, location.world?.name, location.blockX, location.blockY, location.blockZ, failure,
        )
    }

    private companion object {
        const val CLAIM_PENDING: Byte = 1
        const val CLAIM_DELIVERED: Byte = 2
    }
}
