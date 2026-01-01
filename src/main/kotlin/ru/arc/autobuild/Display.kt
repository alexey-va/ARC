package ru.arc.autobuild

import com.destroystokyo.paper.ParticleBuilder
import com.google.common.cache.CacheBuilder
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Location
import org.bukkit.block.data.type.Bed
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.packetevents.BlockDisplayReq
import ru.arc.util.BlockUtils.rotateBlockData
import ru.arc.util.LocationUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles visual display of construction site boundaries.
 *
 * Shows:
 * - Particle border around the building area
 * - Block display entities for preview (if player version supports it)
 */
class Display(private val site: ConstructionSite) {

    companion object {
        private val displayCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build<UUID, Int>()

        /**
         * Clears the display rate limit cache.
         * Should be called on plugin disable.
         */
        @JvmStatic
        fun clearCache() {
            displayCache.invalidateAll()
        }
    }

    private var displayTask: BukkitTask? = null
    private var borderLocations: List<LocationUtils.LocationData>? = null
    private var centerLocations: List<Location>? = null
    private var entityIds = mutableListOf<Int>()

    // ==================== Public API ====================

    fun showBorder(seconds: Int) {
        stopTask()
        startBorderParticles(seconds)
    }

    fun showBorderAndDisplay(seconds: Int) {
        stopTask()
        startBorderParticles(seconds)
        placeDisplayEntities(seconds)
    }

    fun stop() {
        stopTask()
        try {
            removeDisplayEntities()
        } catch (e: Exception) {
            error(e.message ?: "Unknown error")
        }
    }

    // ==================== Border Particles ====================

    private fun startBorderParticles(seconds: Int) {
        if (borderLocations == null) borderLocations = site.getBorderLocations()
        if (centerLocations == null) centerLocations = site.getCenterLocations()

        val interval = BuildConfig.borderParticleInterval
        val elapsed = AtomicInteger(0)
        val maxTicks = seconds * 20

        displayTask = ARC.plugin.server.scheduler.runTaskTimer(ARC.plugin, Runnable {
            if (elapsed.addAndGet(interval.toInt()) > maxTicks) {
                displayTask?.cancel()
                return@Runnable
            }
            showBorderParticles()
            showCenterParticles()
        }, 0L, interval)
    }

    private fun showBorderParticles() {
        val playerLoc = site.player.location
        // Skip if player is in different world
        if (playerLoc.world != site.world) return

        for (locData in borderLocations.orEmpty()) {
            // Skip distant non-corner particles for performance
            if (!locData.corner && playerLoc.distanceSquared(locData.location) > 300) continue

            val isCorner = locData.corner
            ParticleManager.queue(
                ParticleBuilder(BuildConfig.borderParticle)
                    .location(locData.location)
                    .extra(0.0)
                    .offset(
                        if (isCorner) BuildConfig.borderParticleCornerOffset else BuildConfig.borderParticleOffset,
                        if (isCorner) BuildConfig.borderParticleCornerOffset else BuildConfig.borderParticleOffset,
                        if (isCorner) BuildConfig.borderParticleCornerOffset else BuildConfig.borderParticleOffset
                    )
                    .count(if (isCorner) BuildConfig.borderParticleCornerCount else BuildConfig.borderParticleCount)
                    .receivers(listOf(site.player))
            )
        }
    }

    private fun showCenterParticles() {
        val playerLoc = site.player.location
        // Skip if player is in different world
        if (playerLoc.world != site.world) return

        for (location in centerLocations.orEmpty()) {
            if (playerLoc.distanceSquared(location) > 300) continue

            ParticleManager.queue(
                ParticleBuilder(BuildConfig.centerParticle)
                    .location(location)
                    .extra(0.0)
                    .offset(0.0, 0.0, 0.0)
                    .count(BuildConfig.centerParticleCount)
                    .receivers(listOf(site.player))
            )
        }
    }

    // ==================== Display Entities ====================

    private fun placeDisplayEntities(seconds: Int) {
        // Check player version (block displays require 1.19.4+)
        HookRegistry.viaVersionHook?.let { via ->
            if (via.getPlayerVersion(site.player) < 761) return
        }

        val packetHook = HookRegistry.packetEventsHook ?: return

        // Rate limiting
        val currentShows = displayCache.getIfPresent(site.player.uniqueId) ?: 0
        if (currentShows >= BuildConfig.maxDisplaysPer10Min) {
            site.player.sendMessage(BuildConfig.Messages.displayRateLimit())
            return
        }

        val requests = buildDisplayRequests()

        displayCache.put(site.player.uniqueId, currentShows + requests.size)
        entityIds = packetHook.createDisplayBlocks(requests, site.player).toMutableList()

        // Schedule cleanup
        ARC.plugin.server.scheduler.runTaskLater(ARC.plugin, Runnable {
            info("Removing display due to timeout {}", seconds)
            removeDisplayEntities()
        }, seconds * 20L)
    }

    private fun buildDisplayRequests(): List<BlockDisplayReq> {
        val c = site.corners
        val requests = mutableListOf<BlockDisplayReq>()

        val totalBlocks = (c.corner2.x() - c.corner1.x() + 1) *
            (c.corner2.y() - c.corner1.y() + 1) *
            (c.corner2.z() - c.corner1.z() + 1)

        outer@ for (y in c.corner1.y()..c.corner2.y()) {
            for (x in c.corner1.x()..c.corner2.x()) {
                for (z in c.corner1.z()..c.corner2.z()) {
                    val location = Location(
                        site.world,
                        x + site.adjustedCenter.x,
                        y + site.adjustedCenter.y,
                        z + site.adjustedCenter.z
                    )

                    val blockData = BukkitAdapter.adapt(
                        site.building.getBlock(BlockVector3.at(x, y, z), site.fullRotation)
                    ).also { rotateBlockData(it, site.fullRotation) }

                    // Skip bed feet (look weird as displays)
                    if (blockData is Bed && blockData.part == Bed.Part.FOOT) continue

                    // Skip solid blocks (can't see the preview)
                    if (location.block.type.isSolid) continue

                    requests.add(BlockDisplayReq(location, blockData))

                    if (requests.size >= BuildConfig.maxDisplayBlocks) {
                        site.player.sendMessage(BuildConfig.Messages.displayLimit())
                        break@outer
                    }
                }
            }
        }

        return requests
    }

    private fun removeDisplayEntities() {
        HookRegistry.packetEventsHook?.removeDisplayBlocks(entityIds, site.player)
    }

    private fun stopTask() {
        displayTask?.takeIf { !it.isCancelled }?.cancel()
    }
}
