package ru.arc.common.chests

import org.bukkit.Material
import org.bukkit.block.Block

/** Блоковая координата для JSON-реестра (barrier от IA). */
data class BlockPos(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    companion object {
        fun of(block: Block): BlockPos = BlockPos(block.x, block.y, block.z)
    }
}

object FurnitureBarrierTracker {
    /** Куб вокруг anchor — IA ставит barrier не только на соседних блоках. */
    const val SCAN_RADIUS = 2

    fun snapshotBarrierBlocks(
        block: Block,
        radius: Int = SCAN_RADIUS,
    ): Set<BlockPos> {
        val world = block.world ?: return emptySet()
        val result = linkedSetOf<BlockPos>()
        val cx = block.x
        val cy = block.y
        val cz = block.z
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val relative = world.getBlockAt(cx + dx, cy + dy, cz + dz)
                    if (relative.type == Material.BARRIER) {
                        result.add(BlockPos.of(relative))
                    }
                }
            }
        }
        return result
    }

    fun detectSpawned(
        before: Set<BlockPos>,
        after: Set<BlockPos>,
    ): Set<BlockPos> = after - before
}
