package ru.arc.common.chests

import org.bukkit.block.Block
import org.bukkit.entity.Entity
import java.util.UUID

/**
 * Снимок entity вокруг точки спавна IA-мебели (до/после spawn → diff).
 */
fun interface FurnitureEntityScanner {
    fun snapshotNear(
        block: Block,
        radius: Double,
    ): Set<UUID>
}

object FurnitureEntityTracker : FurnitureEntityScanner {
    const val SPAWN_SCAN_RADIUS = 3.0

    override fun snapshotNear(
        block: Block,
        radius: Double,
    ): Set<UUID> {
        val center = block.location.add(0.5, 0.5, 0.5)
        val world = center.world ?: return emptySet()
        return world
            .getNearbyEntities(center, radius, radius, radius)
            .mapTo(linkedSetOf()) { it.uniqueId }
    }

    fun detectSpawned(
        before: Set<UUID>,
        after: Set<UUID>,
    ): Set<UUID> = after - before

    fun mergeWithFurnitureEntity(
        spawned: Set<UUID>,
        furnitureEntity: Entity?,
    ): Set<UUID> {
        if (furnitureEntity == null) return spawned
        return spawned + furnitureEntity.uniqueId
    }
}
