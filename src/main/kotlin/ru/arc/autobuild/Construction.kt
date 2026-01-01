package ru.arc.autobuild

import com.destroystokyo.paper.ParticleBuilder
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.LootContext
import org.bukkit.loot.LootTables
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.util.BlockUtils.rotateBlockData
import ru.arc.util.ParticleManager
import ru.arc.util.RandomUtils
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles the block-by-block construction process.
 *
 * Creates an NPC worker and places blocks over time with effects.
 */
class Construction(private val site: ConstructionSite) {

    private var buildTask: BukkitTask? = null
    private var removeNpcTask: BukkitTask? = null
    private var blocks = mutableListOf<BlockVector3>()

    val pointer = AtomicInteger(-1)
    var lookClose = false
        private set
    var npcId = -1
        private set

    // ==================== NPC ====================

    fun createNpc(location: Location, seconds: Int): Int {
        val citizens = HookRegistry.citizensHook ?: return -1

        val (name, skinUrl) = RandomUtils.random(BuildConfig.npcSkins)
        npcId = citizens.createNpc(name, location.toCenterLocation())

        citizens.addChatBubble(
            npcId, listOf(
                CitizensHook.HologramLine("&6Нажмите ПКМ, чтобы начать строительство", 100)
            )
        )
        citizens.setSkin(npcId, skinUrl)

        if (seconds > 0) {
            removeNpcTask = Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable {
                citizens.deleteNpc(npcId)
            }, 20L * seconds)
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

        // Prepare blocks async, then build sync
        Bukkit.getScheduler().runTaskAsynchronously(ARC.plugin, Runnable {
            prepareBlockList()
            buildTask = Bukkit.getScheduler().runTaskTimer(ARC.plugin, Runnable {
                if (placeNextBlocks(BuildConfig.blocksPerTick)) {
                    buildTask?.cancel()
                    site.complete()
                }
            }, 1L, BuildConfig.cycleDurationTicks)
        })
    }

    private fun prepareBlockList() {
        val c = site.corners
        blocks.clear()

        // Y first for bottom-up building
        for (y in c.corner1.y()..c.corner2.y()) {
            for (x in c.corner1.x()..c.corner2.x()) {
                for (z in c.corner1.z()..c.corner2.z()) {
                    blocks.add(BlockVector3.at(x, y, z))
                }
            }
        }
    }

    private fun placeNextBlocks(count: Int): Boolean {
        var placed = 0

        while (placed < count) {
            val index = pointer.incrementAndGet()
            if (index >= blocks.size) return true

            val vec = blocks[index]
            val location = Location(
                site.world,
                site.centerBlock.x + vec.x(),
                site.centerBlock.y + vec.y() + site.yOffset,
                site.centerBlock.z + vec.z()
            )

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
            currentBlock.blockData = blockData

            // Fill containers
            fillContainerIfNeeded(location)

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

    private fun fillContainerIfNeeded(location: Location) {
        val state = location.block.state
        val context = LootContext.Builder(location).build()

        when (state) {
            is Chest -> LootTables.SPAWN_BONUS_CHEST.lootTable.fillInventory(
                state.inventory,
                ThreadLocalRandom.current(),
                context
            )

            is Barrel -> LootTables.SPAWN_BONUS_CHEST.lootTable.fillInventory(
                state.inventory,
                ThreadLocalRandom.current(),
                context
            )
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
        buildTask?.takeIf { !it.isCancelled }?.cancel()
        removeNpcTask?.takeIf { !it.isCancelled }?.cancel()
        Bukkit.getScheduler().runTaskLater(ARC.plugin, Runnable { destroyNpc() }, destroyNpcDelaySeconds * 20L)
    }

    fun finishInstantly() {
        cancel(0)
        // Ensure blocks are prepared before placing
        if (blocks.isEmpty()) {
            prepareBlockList()
        }
        placeNextBlocks(1_000_000)
    }
}
