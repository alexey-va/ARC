package ru.arc.autobuild

import com.destroystokyo.paper.ParticleBuilder
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.util.BlockUtils.rotateBlockData
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.ParticleManager
import ru.arc.util.RandomUtils
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Applies only the structural block state from a build book. A schematic does
 * not own container inventory data and must never synthesize loot on placement.
 */
internal object ConstructionBlockPlacement {
    fun apply(block: Block, blockData: BlockData) {
        block.blockData = blockData
    }
}

/**
 * Handles the block-by-block construction process.
 *
 * Creates an NPC worker and places blocks over time with effects.
 */
internal class Construction(
    private val site: ConstructionSite,
    private val buildTasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val prepareBlocks: (ConstructionSite) -> List<BlockVector3> = ::prepareBlockList,
) {
    private var removeNpcTask: ScheduledTask? = null
    private var blocks = emptyList<BlockVector3>()

    val pointer = AtomicInteger(-1)
    var lookClose = false
        private set
    var npcId = -1
        private set

    // ==================== NPC ====================

    fun createNpc(location: Location, seconds: Int): Int {
        val citizens = HookRegistry.citizensHook ?: run {
            debug("[autobuild] createNpc skipped: Citizens hook missing for player={}", site.player.name)
            return -1
        }

        val (name, skinUrl) = RandomUtils.random(BuildConfig.npcSkins)
        npcId = citizens.createNpc(name, location.toCenterLocation())

        val promptTicks = if (seconds > 0) (seconds.toLong() * 20L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else -1
        citizens.addChatBubble(
            npcId,
            BuildConfig.npcPrompt.map { CitizensHook.HologramLine(it, promptTicks) },
        )
        citizens.setSkin(npcId, skinUrl)

        if (seconds > 0) {
            removeNpcTask =
                delayed((20L * seconds).ticks) {
                    citizens.deleteNpc(npcId)
                }
            citizens.lookClose(npcId)
            lookClose = true
        }

        return npcId
    }

    fun destroyNpc() {
        if (npcId == -1) return
        removeNpcTask?.takeIf { !it.isCancelled }?.cancel()
        HookRegistry.citizensHook?.deleteNpc(npcId)
    }

    // ==================== Building ====================

    fun startBuilding() {
        if (npcId == -1) createNpc(site.centerBlock, -1)
        if (npcId != -1 && lookClose) HookRegistry.citizensHook?.lookClose(npcId)
        removeNpcTask?.takeIf { !it.isCancelled }?.cancel()

        val token = buildTasks.token()
        buildTasks.runAsync(token) {
            try {
                val preparedBlocks = prepareBlocks(site)
                buildTasks.runSync(token) {
                    blocks = preparedBlocks
                    debug(
                        "[autobuild] startBuilding player={} building={} blocks={} npcId={}",
                        site.player.name,
                        site.building.fileName,
                        blocks.size,
                        npcId,
                    )
                    buildTasks.runTimer(token, 1L, BuildConfig.cycleDurationTicks) {
                        if (placeNextBlocks(BuildConfig.blocksPerTick)) {
                            buildTasks.close()
                            site.complete()
                        }
                    }
                }
            } catch (e: Exception) {
                error("[autobuild] startBuilding failed for player={}", site.player.name, e)
                buildTasks.runSync(token) {
                    if (site.state == ConstructionState.Building) site.cancel()
                }
            }
        }
    }

    private companion object {
        fun prepareBlockList(site: ConstructionSite): List<BlockVector3> {
            val c = site.corners
            val prepared = mutableListOf<BlockVector3>()

            // Y first for bottom-up building
            for (y in c.corner1.y()..c.corner2.y()) {
                for (x in c.corner1.x()..c.corner2.x()) {
                    for (z in c.corner1.z()..c.corner2.z()) {
                        prepared.add(BlockVector3.at(x, y, z))
                    }
                }
            }
            return prepared
        }
    }

    private fun placeNextBlocks(count: Int): Boolean {
        var placed = 0

        while (placed < count) {
            val index = pointer.incrementAndGet()
            if (index >= blocks.size) return true

            val vec = blocks[index]
            val location = site.worldLocation(vec)

            val blockData = BukkitAdapter.adapt(site.building.getBlock(vec, site.fullRotation)).also {
                rotateBlockData(it, site.fullRotation)
            }

            val currentBlock = site.world.getBlockAt(location)

            // Skip conditions
            when {
                blockData.material == Material.AIR && currentBlock.type == Material.AIR -> continue
                blockData == currentBlock.blockData -> continue
                currentBlock.type in BuildConfig.skipMaterials -> continue
                HookRegistry.sfHook?.isSlimefunBlock(currentBlock) == true -> continue
            }

            // Give drops for replaced blocks
            if (currentBlock.type != blockData.material && currentBlock.type !in BuildConfig.nonDropMaterials) {
                giveDrops(currentBlock)
            }

            // Place block
            ConstructionBlockPlacement.apply(currentBlock, blockData)

            // Effects on first block of tick (only if player is in same world and nearby)
            if (placed == 0 && isPlayerNearby()) {
                playEffects(location, blockData)
            }

            placed++
        }

        return false
    }

    private fun giveDrops(block: org.bukkit.block.Block) {
        for (drop in block.drops) {
            val leftover = site.player.inventory.addItem(drop)
            for (item in leftover.values) {
                site.player.world.dropItem(site.player.location, item)
            }
        }
    }

    private fun playEffects(location: Location, blockData: org.bukkit.block.data.BlockData) {
        // Sound
        if (BuildConfig.playSounds) {
            location.world?.playSound(location, blockData.soundGroup.placeSound, 1f, 1f)
        }

        // Particles
        if (BuildConfig.showParticles) {
            ParticleManager.queue(
                ParticleBuilder(BuildConfig.placeParticle)
                    .count(BuildConfig.particleCount)
                    .location(location)
                    .receivers(listOf(site.player))
                    .offset(BuildConfig.particleOffset, BuildConfig.particleOffset, BuildConfig.particleOffset)
                    .extra(0.05)
            )
        }

        // NPC animations
        if (npcId != -1) {
            HookRegistry.citizensHook?.let { hook ->
                if (ThreadLocalRandom.current().nextDouble() > 0.8) {
                    hook.faceNpc(npcId, location)
                }
                if (blockData.material.isItem) {
                    hook.setMainHand(npcId, ItemStack(blockData.material))
                }
                hook.animateNpc(npcId, CitizensHook.Animation.ARM_SWING)
            }
        }
    }

    // ==================== Utilities ====================

    private fun isPlayerNearby(): Boolean {
        val playerLoc = site.player.location
        return playerLoc.world == site.world &&
            playerLoc.distanceSquared(site.centerBlock) < 2500 // 50^2
    }

    // ==================== Lifecycle ====================

    fun cancel(destroyNpcDelaySeconds: Int) {
        buildTasks.close()
        removeNpcTask?.takeIf { !it.isCancelled }?.cancel()
        removeNpcTask =
            if (destroyNpcDelaySeconds <= 0) {
                destroyNpc()
                null
            } else {
                delayed((destroyNpcDelaySeconds * 20L).ticks) { destroyNpc() }
            }
    }

    fun finishInstantly() {
        cancel(0)
        // Ensure blocks are prepared before placing
        if (blocks.isEmpty()) {
            blocks = prepareBlocks(site)
        }
        placeNextBlocks(1_000_000)
    }
}
