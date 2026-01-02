package ru.arc.farm

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldguard.WorldGuard
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block

/**
 * Interface for region checking.
 *
 * Abstracts WorldGuard to enable testing.
 */
interface FarmRegion {
    /**
     * Check if location is within this region.
     */
    fun contains(location: Location): Boolean

    /**
     * Check if coordinates are within this region.
     */
    fun contains(x: Int, y: Int, z: Int): Boolean

    /**
     * Get all blocks in the region (for caching).
     */
    fun getBlocks(): Sequence<Block>

    /**
     * Get the world this region is in.
     */
    fun getWorld(): World?

    /**
     * Check if region is valid (loaded properly).
     */
    fun isValid(): Boolean
}

/**
 * WorldGuard implementation of FarmRegion.
 */
class WorldGuardFarmRegion(
    private val world: World?,
    private val regionName: String
) : FarmRegion {

    private val region by lazy {
        if (world == null) return@lazy null

        val container = WorldGuard.getInstance().platform.regionContainer
        val manager = container.get(BukkitAdapter.adapt(world))
        manager?.getRegion(regionName)
    }

    override fun contains(location: Location): Boolean {
        if (location.world != world) return false
        return contains(location.blockX, location.blockY, location.blockZ)
    }

    override fun contains(x: Int, y: Int, z: Int): Boolean {
        return region?.contains(x, y, z) == true
    }

    override fun getBlocks(): Sequence<Block> {
        val r = region ?: return emptySequence()
        val w = world ?: return emptySequence()

        return sequence {
            val cuboid = CuboidRegion(
                BukkitAdapter.adapt(w),
                r.minimumPoint,
                r.maximumPoint
            )
            for (vec in cuboid) {
                yield(w.getBlockAt(vec.x(), vec.y(), vec.z()))
            }
        }
    }

    override fun getWorld(): World? = world

    override fun isValid(): Boolean = region != null && world != null
}

/**
 * Simple bounds-based region for testing.
 */
class SimpleFarmRegion(
    private val world: World?,
    private val bounds: RegionBounds
) : FarmRegion {

    override fun contains(location: Location): Boolean {
        if (location.world != world) return false
        return contains(location.blockX, location.blockY, location.blockZ)
    }

    override fun contains(x: Int, y: Int, z: Int): Boolean {
        return bounds.contains(x, y, z)
    }

    override fun getBlocks(): Sequence<Block> {
        val w = world ?: return emptySequence()

        return sequence {
            for (x in bounds.minX..bounds.maxX) {
                for (y in bounds.minY..bounds.maxY) {
                    for (z in bounds.minZ..bounds.maxZ) {
                        yield(w.getBlockAt(x, y, z))
                    }
                }
            }
        }
    }

    override fun getWorld(): World? = world

    override fun isValid(): Boolean = world != null
}

/**
 * Factory for creating FarmRegion instances.
 */
interface FarmRegionFactory {
    fun create(worldName: String, regionName: String): FarmRegion
}

/**
 * Production factory using WorldGuard.
 */
class WorldGuardRegionFactory : FarmRegionFactory {
    override fun create(worldName: String, regionName: String): FarmRegion {
        val world = org.bukkit.Bukkit.getWorld(worldName)
        return WorldGuardFarmRegion(world, regionName)
    }
}

/**
 * Test factory for creating simple regions.
 */
class TestRegionFactory(
    private val worldProvider: (String) -> World?
) : FarmRegionFactory {

    private val regions = mutableMapOf<String, RegionBounds>()

    fun registerRegion(regionName: String, bounds: RegionBounds) {
        regions[regionName] = bounds
    }

    override fun create(worldName: String, regionName: String): FarmRegion {
        val world = worldProvider(worldName)
        val bounds = regions[regionName] ?: RegionBounds(0, 0, 0, 0, 0, 0)
        return SimpleFarmRegion(world, bounds)
    }
}


