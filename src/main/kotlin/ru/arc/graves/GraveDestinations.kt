package ru.arc.graves

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World

/** Bounded owning-thread search in the grave's already-prepared chunk only. */
internal object GraveDestinations {
    private val hazards = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS,
        Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
        Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.WATER)

    fun nearby(world: World, x: Int, y: Int, z: Int): Location? {
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
        // Include the surface for void/lava deaths, without scanning/generating adjacent chunks.
        val ys = ((y - 4)..(y + 4)).sortedBy { kotlin.math.abs(it - y) }
        for (feet in ys.distinct()) {
            if (feet <= world.minHeight || feet + 1 >= world.maxHeight) continue
            val target = Location(world, x + .5, feet.toDouble(), z + .5)
            if (safe(target)) return target
        }
        return null
    }

    fun safe(target: Location): Boolean {
        val world = target.world
        val x = target.blockX; val y = target.blockY; val z = target.blockZ
        if (!world.isChunkLoaded(x shr 4, z shr 4) || y <= world.minHeight || y + 1 >= world.maxHeight ||
            !world.worldBorder.isInside(target) || (world.environment == World.Environment.NETHER && y >= 128)) return false
        val ground = world.getBlockAt(x, y - 1, z)
        val lower = world.getBlockAt(x, y, z)
        val upper = world.getBlockAt(x, y + 1, z)
        return ground.type !in hazards && ground.type.isSolid && lower.type !in hazards && upper.type !in hazards &&
            lower.isPassable && upper.isPassable && ground.boundingBox.height >= .99
    }
}
