package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.util.Logging
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** A detached generation; both reward kinds share the existing permanent chunk allowance. */
internal class EnchantmentMiningLootSettings(
    val survivalBackend: Boolean,
    worldNames: Set<String>,
    materials: Set<Material>,
    val chancePercent: Double,
    val keyChancePercent: Double = 0.0,
    testOverrides: Map<String, EnchantmentFindChances> = emptyMap(),
) {
    val worldNames: Set<String> = Collections.unmodifiableSet(worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) })
    val materials: Set<Material> = Collections.unmodifiableSet(materials.toSet())
    val testOverrides: Map<String, EnchantmentFindChances> = immutableEnchantmentFindTestOverrides(testOverrides)
    private val baseChances = EnchantmentFindChances(chancePercent, keyChancePercent)

    init {
        require(worldNames.none(String::isBlank)) { "Mining loot world names must not be blank" }
        require(this.worldNames.size == worldNames.size) { "Mining loot world names must be unique ignoring case" }
        require(materials.all { it.isBlock && !it.isAir }) { "Mining loot materials must be non-air blocks" }
    }

    fun allowsWorld(worldName: String): Boolean = survivalBackend && worldName.lowercase(Locale.ROOT) in worldNames

    fun chancesFor(playerName: String): EnchantmentFindChances = testOverrides[playerName.lowercase(Locale.ROOT)] ?: baseChances
}

internal val enchantmentMiningChunkClaimKey = NamespacedKey("arc", "ae_mining_lootbox_claim_v1")

/** A successful manual mine exposes a visible find on one natural neighbour, never an immediate extra drop. */
internal class EnchantmentMiningLoot(
    private val settings: EnchantmentMiningLootSettings,
    private val finds: EnchantmentVisibleFinds,
    private val isNaturalBlock: (Block) -> Boolean = { false },
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val currentTick: () -> Int = Bukkit::getCurrentTick,
) : Listener, AutoCloseable {
    private var closed = false
    private var failureReported = false

    init { finds.register(settings) }

    internal fun settingsSnapshot(): EnchantmentMiningLootSettings = settings

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (closed || event.isCancelled || !settings.allowsWorld(event.block.world.name) || event.block.type !in settings.materials) return
        safely {
            val player = event.player
            val block = event.block
            if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return@safely
            val eligible = player.isOnline && player.gameMode == GameMode.SURVIVAL && player.world.uid == block.world.uid &&
                Tag.ITEMS_PICKAXES.isTagged(player.inventory.itemInMainHand.type) && isNaturalBlock(block)
            if (finds.isFind(block, EnchantmentFindSource.MINING)) {
                finds.captureBreak(event, EnchantmentFindSource.MINING, eligible)
                return@safely
            }
            if (!eligible || !event.isDropItems) return@safely
            val chances = settings.chancesFor(player.name)
            if (chances.chancePercent + chances.keyChancePercent == 0.0 ||
                !finds.available(block, EnchantmentFindSource.MINING)) return@safely
            if (!finds.admitRoll(player.uniqueId, currentTick())) return@safely
            val reward = enchantmentFindReward(nextDouble(), chances.chancePercent, chances.keyChancePercent) ?: return@safely
            finds.captureBreak(event, EnchantmentFindSource.MINING, true) {
                safely {
                    if (closed || !block.world.isChunkLoaded(block.x shr 4, block.z shr 4) || !block.type.isAir) return@safely
                    val candidates = FACES.mapNotNull { direction ->
                        val x = block.x + direction.modX
                        val y = block.y + direction.modY
                        val z = block.z + direction.modZ
                        if (x shr 4 != block.x shr 4 || z shr 4 != block.z shr 4 || y !in block.world.minHeight..<block.world.maxHeight) null
                        else block.world.getBlockAt(x, y, z).takeIf {
                            it.type in settings.materials && finds.available(it, EnchantmentFindSource.MINING) && isNaturalBlock(it)
                        }?.let { it to direction.oppositeFace }
                    }
                    val (host, exposedFace) = candidates.randomOrNull() ?: return@safely
                    finds.reveal(host, EnchantmentFindSource.MINING, exposedFace, reward, isNaturalBlock)
                }
            }
        }
    }

    private inline fun safely(action: () -> Unit) {
        try { action() }
        catch (failure: Exception) { report(failure) }
        catch (failure: LinkageError) { report(failure) }
    }

    private fun report(failure: Throwable) {
        if (failureReported) return
        failureReported = true
        Logging.error("AE mining visible find failed; further failures are suppressed until reload", failure)
    }

    override fun close() { closed = true }

    private companion object {
        val FACES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
    }
}
