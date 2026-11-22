package ru.arc.aurora

import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.Snow
import kotlin.math.floor

private const val MAX_BRIDGE_DEPTH = 10

internal val AuroraShape.followsTerrain: Boolean get() = when (this) {
    AuroraShape.INVISIBLE_HARE, AuroraShape.PAPER_FLOTILLA, AuroraShape.TUMBLEWEED -> true
    else -> false
}

internal data class AuroraSurface(val blockY: Int, val height: Double)

/** Main only. The flotilla may sail under a deck when its entire hull has headroom. */
internal fun terrainSurfaceAt(
    world: World, shape: AuroraShape, x: Int, z: Int, waterline: Int? = null, clearance: Double = 0.0,
): AuroraSurface? {
    if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
    val y = waterline ?: world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
    if (y !in world.minHeight until world.maxHeight) return null
    if (shape == AuroraShape.PAPER_FLOTILLA) {
        val bottom = waterline ?: maxOf(y - MAX_BRIDGE_DEPTH, world.minHeight)
        for (waterY in y downTo bottom) {
            val block = world.getBlockAt(x, waterY, z)
            if (block.type != Material.WATER) continue
            if ((block.blockData as? Levelled)?.level != 0) return null
            val height = waterY + 8.0 / 9.0 // Vanilla standing water has amount 8 with air above it.
            val top = maxOf(waterY + 1, floor(height + clearance).toInt())
            if (top >= world.maxHeight || (waterY + 1..top).any { !world.getBlockAt(x, it, z).type.isAir }) return null
            return AuroraSurface(waterY, height)
        }
        return null
    }
    // Open-top snow and sand. A roof, plant, steeper dune or other support prevents contact.
    val block = world.getBlockAt(x, y, z)
    val depth = when (shape) {
        AuroraShape.INVISIBLE_HARE -> when (block.type) {
            Material.SNOW_BLOCK -> 1.0
            Material.SNOW -> (block.blockData as? Snow)?.layers?.div(8.0) ?: return null
            else -> return null
        }
        AuroraShape.TUMBLEWEED -> {
            if (block.type != Material.SAND && block.type != Material.RED_SAND) return null
            1.0
        }
        else -> return null
    }
    return AuroraSurface(y, y + depth)
}
