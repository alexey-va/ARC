package ru.arc.farm

import com.destroystokyo.paper.ParticleBuilder
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.util.CooldownManager
import ru.arc.util.ParticleManager
import ru.arc.util.RandomUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm

/**
 * Result of processing a block break event.
 */
sealed class BreakResult {
    /** Event was not handled by this zone */
    object NotHandled : BreakResult()

    /** Event handled, block was broken normally */
    object Allowed : BreakResult()

    /** Event handled, block break was cancelled */
    object Cancelled : BreakResult()
}

/**
 * Base interface for all farm zones.
 */
interface FarmZone {
    val id: String
    val priority: Int get() = 0
    
    /**
     * Process a block break event.
     * @return true if event was handled (even if cancelled)
     */
    fun processBreak(event: BlockBreakEvent): BreakResult

    /**
     * Reset daily limits.
     */
    fun resetLimits()

    /**
     * Cleanup resources.
     */
    fun cleanup()
}

/**
 * Farm zone for harvesting crops.
 *
 * Features:
 * - Auto-replant crops at age 0
 * - Daily block limit per player
 * - Particles on harvest
 */
class CropFarm(
    override val id: String,
    override val priority: Int,
    private val config: FarmZoneConfig,
    private val region: FarmRegion,
    private val adminPermission: String,
    private val messages: FarmMessages = FarmMessages(),
    private val limitTracker: BlockLimitTracker = BlockLimitTracker(config.maxBlocksPerDay, 64)
) : FarmZone {

    override fun processBreak(event: BlockBreakEvent): BreakResult {
        if (!region.isValid()) return BreakResult.NotHandled

        val block = event.block
        if (!region.contains(block.location)) return BreakResult.NotHandled

        // Admin bypass
        if (event.player.hasPermission(adminPermission)) return BreakResult.Allowed

        event.isCancelled = true

        // Permission check
        if (!event.player.hasPermission(config.permission)) {
            event.player.sendMessage(TextUtil.noWGPermission())
            return BreakResult.Cancelled
        }

        // Material check
        if (block.type !in config.blocks) {
            event.player.sendMessage(TextUtil.noWGPermission())
            return BreakResult.Cancelled
        }

        // Limit check
        if (limitTracker.hasReachedLimit(event.player.uniqueId)) {
            sendLimitMessage(event.player)
            return BreakResult.Cancelled
        }

        // Harvest the crop
        harvestCrop(event.player, block)
        limitTracker.incrementBlocks(event.player.uniqueId)

        if (limitTracker.shouldShowProgress(event.player.uniqueId)) {
            sendProgressMessage(event.player)
        }

        return BreakResult.Cancelled // Event is cancelled, but we handled the harvest
    }

    private fun harvestCrop(player: Player, block: Block) {
        val blockData = block.blockData

        if (blockData is Ageable) {
            // Only harvest fully grown crops
            if (blockData.age != blockData.maximumAge) return

            // Give drops (excluding seeds)
            val drops = block.drops
                .filter { it.type !in config.seeds }
                .toTypedArray()
            player.inventory.addItem(*drops)

            // Replant
            blockData.age = 0
            block.setBlockData(blockData, false)
        } else {
            block.breakNaturally()
        }

        if (config.particles) {
            spawnParticles(player, block)
        }
    }

    private fun spawnParticles(player: Player, block: Block) {
        val particle = RandomUtils.random(arrayOf(Particle.FLAME, Particle.END_ROD, Particle.CRIT))
        ParticleManager.queue(
            ParticleBuilder(particle)
                .location(block.location.toCenterLocation())
                .count(5)
                .extra(0.06)
                .offset(0.25, 0.25, 0.25)
                .receivers(listOf(player))
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
        // Nothing to cleanup
    }
}

/**
 * Lumbermill zone for cutting trees.
 *
 * Features:
 * - Allow breaking specific log types
 * - Particles on break
 * - No limits (trees regrow naturally)
 */
class Lumbermill(
    override val id: String,
    override val priority: Int,
    private val config: LumbermillConfig,
    private val region: FarmRegion,
    private val adminPermission: String,
    private val messages: FarmMessages = FarmMessages()
) : FarmZone {

    override fun processBreak(event: BlockBreakEvent): BreakResult {
        if (!region.isValid()) return BreakResult.NotHandled

        val block = event.block
        if (!region.contains(block.location)) return BreakResult.NotHandled

        // Admin bypass
        if (event.player.hasPermission(adminPermission)) return BreakResult.Allowed

        // Permission + material check
        if (!event.player.hasPermission(config.permission) || block.type !in config.blocks) {
            event.player.sendMessage(TextUtil.noWGPermission())
            event.isCancelled = true
            return BreakResult.Cancelled
        }

        // Allow normal break with particles
        if (config.particles) {
            spawnParticles(event.player, block)
        }

        return BreakResult.Allowed
    }

    private fun spawnParticles(player: Player, block: Block) {
        val particle = RandomUtils.random(arrayOf(Particle.CRIT, Particle.FLAME, Particle.END_ROD))
        ParticleManager.queue(
            ParticleBuilder(particle)
                .location(block.location.toCenterLocation())
                .count(5)
                .extra(0.06)
                .offset(0.25, 0.25, 0.25)
                .receivers(listOf(player))
        )
    }

    override fun resetLimits() {
        // No limits for lumbermill
    }

    override fun cleanup() {
        // Nothing to cleanup
    }
}

