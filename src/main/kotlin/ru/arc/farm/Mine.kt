package ru.arc.farm

import com.destroystokyo.paper.ParticleBuilder
import com.jeff_media.customblockdata.CustomBlockData
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.common.WeightedRandom
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.CooldownManager
import ru.arc.util.ParticleManager
import ru.arc.util.RandomUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import java.util.concurrent.ThreadLocalRandom

/**
 * Mine zone for breaking ores.
 *
 * Features:
 * - Weighted random ore generation
 * - Temporary blocks that regenerate after time
 * - Daily block limit per player
 * - Experience on mining
 * - Particles on break
 */
class Mine(
    override val id: String,
    override val priority: Int,
    private val config: MineConfig,
    private val region: FarmRegion,
    private val adminPermission: String,
    private val plugin: Plugin,
    private val scheduler: TaskScheduler,
    private val messages: FarmMessages = FarmMessages(),
    private val limitTracker: BlockLimitTracker = BlockLimitTracker(config.maxBlocksPerDay, 16),
    private val orePicker: WeightedRandom<Material> = createOrePicker(config.oreWeights),
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : FarmZone {

    companion object {
        private fun createOrePicker(oreWeights: Map<Material, Int>): WeightedRandom<Material> {
            val picker = WeightedRandom<Material>()
            for ((material, weight) in oreWeights) {
                picker.add(material, weight.toDouble())
            }
            return picker
        }
    }

    private val tempBlockKey = NamespacedKey(plugin, "t")
    private val tempBlocks = TemporaryBlockTracker<Block>(config.expireTimeMs, timeProvider)
    private var brokenBlocks = 0
    private var blockCache: List<Block>? = null

    private var regenerateTask: ScheduledTask? = null
    private var respawnTask: ScheduledTask? = null

    /**
     * Start background tasks.
     */
    fun start() {
        computeBlockCache(replaceTemp = true)

        regenerateTask = scheduler.runTimer(20L, config.replaceTime) {
            processExpiredBlocks()
        }

        respawnTask = scheduler.runTimer(25L, config.replaceTime) {
            respawnOres()
        }
    }

    /**
     * Stop background tasks.
     */
    fun stop() {
        regenerateTask?.cancel()
        respawnTask?.cancel()
        regenerateTask = null
        respawnTask = null
    }

    override fun processBreak(event: BlockBreakEvent): BreakResult {
        if (!region.isValid()) return BreakResult.NotHandled

        val block = event.block
        if (!region.contains(block.location)) return BreakResult.NotHandled

        // Admin bypass
        if (event.player.hasPermission(adminPermission)) return BreakResult.Allowed

        event.isCancelled = true

        // Check if block is still regenerating
        val blockData = CustomBlockData(block, plugin)
        if (blockData.has(tempBlockKey)) {
            event.player.sendActionBar(mm(messages.alreadyBroken))
            return BreakResult.Cancelled
        }

        // Permission + material check
        if (!event.player.hasPermission(config.permission) || block.type !in config.ores) {
            event.player.sendMessage(TextUtil.noWGPermission())
            return BreakResult.Cancelled
        }

        // Limit check
        if (limitTracker.hasReachedLimit(event.player.uniqueId)) {
            sendLimitMessage(event.player)
            return BreakResult.Cancelled
        }

        // Only count towards limit if not base block
        if (block.type != config.baseBlock) {
            limitTracker.incrementBlocks(event.player.uniqueId)
        }

        // Give drops and exp
        mineBlock(event.player, block)

        if (limitTracker.shouldShowProgress(event.player.uniqueId)) {
            sendProgressMessage(event.player)
        }

        return BreakResult.Cancelled
    }

    private fun mineBlock(player: Player, block: Block) {
        // Give drops
        block.drops.forEach { player.inventory.addItem(it) }

        // Give exp
        val exp = if (block.type == config.baseBlock) config.expPerBase else config.expPerOre
        player.giveExp(exp)

        // Replace with temp block
        block.setType(config.tempBlock)
        brokenBlocks++

        // Mark as temp
        val blockData = CustomBlockData(block, plugin)
        blockData.set(tempBlockKey, PersistentDataType.BOOLEAN, true)
        tempBlocks.add(block)

        // Particles
        if (config.particles) {
            spawnParticles(player, block)
        }
    }

    private fun processExpiredBlocks() {
        val expired = tempBlocks.getExpired()
        for (block in expired) {
            val blockData = CustomBlockData(block, plugin)
            blockData.remove(tempBlockKey)
            block.setType(config.baseBlock)
        }
    }

    private fun respawnOres() {
        if (brokenBlocks <= 0) return

        val cache = blockCache ?: return
        if (cache.isEmpty()) return

        val indicesToCheck = (0 until config.replaceBatch)
            .map { ThreadLocalRandom.current().nextInt(cache.size) }
            .toSet()

        for (idx in indicesToCheck) {
            val block = cache[idx]
            if (block.type != config.baseBlock) continue

            var material = orePicker.random() ?: continue

            // Don't spawn falling blocks over air
            val below = block.getRelative(0, -1, 0)
            if (below.type == Material.AIR) {
                material = when (material) {
                    Material.SAND -> Material.SANDSTONE
                    Material.GRAVEL -> Material.STONE
                    else -> material
                }
            }

            block.setType(material)
            brokenBlocks--

            if (brokenBlocks <= 0) return
        }
    }

    private fun computeBlockCache(replaceTemp: Boolean) {
        val blocks = mutableListOf<Block>()

        for (block in region.getBlocks()) {
            // Replace temp blocks on startup
            if (replaceTemp && block.type == config.tempBlock) {
                block.setType(config.baseBlock)
                CustomBlockData(block, plugin).remove(tempBlockKey)
            }

            // Only cache blocks adjacent to air (visible)
            if (isAdjacentToAir(block)) {
                blocks.add(block)
            }
        }

        blockCache = blocks
    }

    private fun isAdjacentToAir(block: Block): Boolean {
        return block.getRelative(1, 0, 0).type == Material.AIR ||
            block.getRelative(-1, 0, 0).type == Material.AIR ||
            block.getRelative(0, 1, 0).type == Material.AIR ||
            block.getRelative(0, -1, 0).type == Material.AIR ||
            block.getRelative(0, 0, 1).type == Material.AIR ||
            block.getRelative(0, 0, -1).type == Material.AIR
    }

    private fun spawnParticles(player: Player, block: Block) {
        val particle = RandomUtils.random(arrayOf(Particle.FLAME, Particle.END_ROD, Particle.CRIT))
        ParticleManager.queue(
            ParticleBuilder(particle)
                .receivers(listOf(player))
                .location(block.location.toCenterLocation())
                .count(5)
                .extra(0.06)
                .offset(0.25, 0.25, 0.25)
        )
    }

    private fun sendLimitMessage(player: Player) {
        if (CooldownManager.cooldown(player.uniqueId, "farm_limit_message") > 0) return
        CooldownManager.addCooldown(player.uniqueId, "farm_limit_message", messages.limitMessageCooldown.toLong())
        player.sendMessage(mm(messages.limitReached))
    }

    private fun sendProgressMessage(player: Player) {
        val count = limitTracker.getBlockCount(player.uniqueId)
        val text = mm(
            messages.progress,
            TagResolver.builder()
                .tag("count", Tag.inserting(mm(count.toString())))
                .tag("max", Tag.inserting(mm(config.maxBlocksPerDay.toString())))
                .build()
        )
        player.sendActionBar(text)
    }

    override fun resetLimits() {
        limitTracker.resetAll()
    }

    override fun cleanup() {
        stop()
    }
}

