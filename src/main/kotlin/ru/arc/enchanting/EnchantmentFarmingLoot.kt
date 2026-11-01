package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.util.Logging
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** A detached generation; boxes and keys share the existing chunk cooldown. */
internal class EnchantmentFarmingLootSettings(
    val survivalBackend: Boolean,
    worldNames: Set<String>,
    materials: Set<Material>,
    val chancePercent: Double,
    val cooldownSeconds: Long,
    val keyChancePercent: Double = 0.0,
    testOverrides: Map<String, EnchantmentFindChances> = emptyMap(),
    creativeTestPlayers: Set<String> = emptySet(),
) {
    val worldNames: Set<String> = Collections.unmodifiableSet(worldNames.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) })
    val materials: Set<Material> = Collections.unmodifiableSet(materials.toSet())
    val testOverrides: Map<String, EnchantmentFindChances> = immutableEnchantmentFindTestOverrides(testOverrides)
    val creativeTestPlayers: Set<String> = immutableEnchantmentFindCreativePlayers(creativeTestPlayers, this.testOverrides.keys)
    private val baseChances = EnchantmentFindChances(chancePercent, keyChancePercent)

    init {
        require(worldNames.none(String::isBlank)) { "Farming loot world names must not be blank" }
        require(this.worldNames.size == worldNames.size) { "Farming loot world names must be unique ignoring case" }
        require(materials.all { it.isBlock && !it.isAir }) { "Farming loot materials must be non-air blocks" }
        require(cooldownSeconds in 1L..31_536_000L) { "Farming loot cooldown must be between 1 and 31536000 seconds" }
    }

    fun allowsWorld(worldName: String): Boolean = survivalBackend && worldName.lowercase(Locale.ROOT) in worldNames

    fun chancesFor(playerName: String): EnchantmentFindChances = testOverrides[playerName.lowercase(Locale.ROOT)] ?: baseChances

    fun allowsGameMode(playerName: String, gameMode: GameMode): Boolean =
        allowsEnchantmentFindGameMode(playerName, gameMode, creativeTestPlayers)
}

internal val enchantmentFarmingChunkClaimKey = NamespacedKey("arc", "ae_farming_lootbox_at_v1")

/** Only manual mature-crop harvest attempts reveal a prop on an adjacent still-grown plant. */
internal class EnchantmentFarmingLoot(
    private val settings: EnchantmentFarmingLootSettings,
    private val finds: EnchantmentVisibleFinds,
    private val nextDouble: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val currentTick: () -> Int = Bukkit::getCurrentTick,
) : Listener, AutoCloseable {
    private var closed = false
    private var failureReported = false

    init { finds.register(settings) }

    internal fun settingsSnapshot(): EnchantmentFarmingLootSettings = settings

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (closed || event.isCancelled || !settings.allowsWorld(event.block.world.name) || event.block.type !in settings.materials) return
        safely {
            val player = event.player
            val block = event.block
            if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return@safely
            val eligible = player.isOnline && settings.allowsGameMode(player.name, player.gameMode) && player.world.uid == block.world.uid && mature(block)
            val creativeTestBreak = eligible && player.gameMode == GameMode.CREATIVE
            if (finds.isFind(block, EnchantmentFindSource.FARMING)) {
                finds.captureBreak(event, EnchantmentFindSource.FARMING, eligible, creativeTestBreak)
                return@safely
            }
            if (!eligible || !event.isDropItems) return@safely
            val chances = settings.chancesFor(player.name)
            if (chances.chancePercent + chances.keyChancePercent == 0.0 ||
                !finds.available(block, EnchantmentFindSource.FARMING)) return@safely
            if (!finds.admitRoll(player.uniqueId, currentTick())) return@safely
            val reward = enchantmentFindReward(nextDouble(), chances.chancePercent, chances.keyChancePercent) ?: return@safely
            finds.captureBreak(event, EnchantmentFindSource.FARMING, true, creativeTestBreak) {
                safely {
                    if (closed || !block.world.isChunkLoaded(block.x shr 4, block.z shr 4) || !block.type.isAir) return@safely
                    val candidates = NEIGHBOURS.mapNotNull { (dx, dz) ->
                        val x = block.x + dx
                        val z = block.z + dz
                        if (x shr 4 != block.x shr 4 || z shr 4 != block.z shr 4) null
                        else block.world.getBlockAt(x, block.y, z).takeIf {
                            it.type in settings.materials && mature(it) && finds.available(it, EnchantmentFindSource.FARMING)
                        }
                    }
                    val host = candidates.randomOrNull() ?: return@safely
                    // Face the harvested gap; diagonal neighbours use the nearest cardinal face.
                    val exposedFace = when {
                        host.x > block.x -> BlockFace.WEST
                        host.x < block.x -> BlockFace.EAST
                        host.z > block.z -> BlockFace.NORTH
                        else -> BlockFace.SOUTH
                    }
                    finds.reveal(host, EnchantmentFindSource.FARMING, exposedFace, reward)
                }
            }
        }
    }

    private fun mature(block: Block): Boolean {
        val crop = block.blockData as? Ageable ?: return false
        return crop.age == crop.maximumAge
    }

    private inline fun safely(action: () -> Unit) {
        try { action() }
        catch (failure: Exception) { report(failure) }
        catch (failure: LinkageError) { report(failure) }
    }

    private fun report(failure: Throwable) {
        if (failureReported) return
        failureReported = true
        Logging.error("AE farming visible find failed; further failures are suppressed until reload", failure)
    }

    override fun close() { closed = true }

    private companion object {
        val NEIGHBOURS = listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
    }
}
