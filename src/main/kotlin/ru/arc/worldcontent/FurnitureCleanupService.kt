package ru.arc.worldcontent

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

data class CleanupScan(
    val plan: FurnitureCleanupPlan,
    val skippedUnloadedChunks: Int,
)

data class CleanupExecution(
    val removedFurniture: Int,
    val removedBarriers: Int,
    val failedFurniture: List<UUID>,
)

object FurnitureCleanupService {
    fun scan(
        center: Location,
        radius: Int,
        runtime: FurnitureRuntime = ItemsAdderFurnitureRuntime,
    ): CleanupScan {
        val world = center.world ?: throw IllegalArgumentException("center world is missing")
        require(radius in 1..FurnitureCleanupPlan.MAX_RADIUS) {
            "radius must be in 1-${FurnitureCleanupPlan.MAX_RADIUS}"
        }
        check(runtime.available) { "ItemsAdder is not enabled" }

        val candidates = mutableListOf<CleanupTarget>()
        val radiusSquared = radius.toDouble() * radius
        world.getNearbyEntities(center, radius.toDouble(), radius.toDouble(), radius.toDouble())
            .asSequence()
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .mapNotNull(runtime::inspect)
            .forEach { handle ->
                candidates += CleanupTarget.Furniture(handle.root.uniqueId, handle.family, handle.namespacedId)
            }

        val skippedChunks = mutableSetOf<Long>()
        forEachBlockInSphere(world, center, radius) { x, y, z ->
            val chunkX = x shr 4
            val chunkZ = z shr 4
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                skippedChunks += (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)
            } else if (world.getBlockAt(x, y, z).type == Material.BARRIER) {
                candidates += CleanupTarget.Barrier(BlockPosition(world.name, x, y, z))
            }
        }

        val cleanupCenter = CleanupCenter(world.name, center.blockX, center.blockY, center.blockZ)
        return CleanupScan(
            FurnitureCleanupPlan.create(cleanupCenter, radius, candidates),
            skippedChunks.size,
        )
    }

    fun execute(
        plan: FurnitureCleanupPlan,
        runtime: FurnitureRuntime = ItemsAdderFurnitureRuntime,
    ): CleanupExecution {
        val world = Bukkit.getWorld(plan.center.world)
            ?: throw IllegalStateException("World is not loaded: ${plan.center.world}")
        var furniture = 0
        var barriers = 0
        val failed = mutableListOf<UUID>()

        plan.targets.forEach { target ->
            when (target) {
                is CleanupTarget.Furniture -> {
                    val entity = world.getEntity(target.rootUuid)
                    if (entity == null || !entity.isValid) {
                        return@forEach
                    }
                    if (runtime.remove(entity, target.family)) {
                        furniture++
                    } else {
                        failed += target.rootUuid
                    }
                }

                is CleanupTarget.Barrier -> {
                    val position = target.position
                    val block = world.getBlockAt(position.x, position.y, position.z)
                    if (block.type == Material.BARRIER) {
                        block.setType(Material.AIR, false)
                        barriers++
                    }
                }
            }
        }
        return CleanupExecution(furniture, barriers, failed)
    }

    /**
     * Precise cleanup for owners such as treasure hunt which already persisted the
     * spawned entity UUIDs and barrier coordinates. Unlike radius cleanup this never
     * discovers or removes neighbouring world content.
     */
    fun executeKnown(
        anchor: Location,
        entityIds: Collection<UUID>,
        barriers: Collection<BlockPosition>,
        runtime: FurnitureRuntime = ItemsAdderFurnitureRuntime,
    ): CleanupExecution {
        val world = anchor.world ?: throw IllegalArgumentException("anchor world is missing")
        check(runtime.available) { "ItemsAdder is not enabled" }
        val candidates = mutableListOf<CleanupTarget>()
        entityIds.forEach { uuid ->
            val entity = world.getEntity(uuid) ?: return@forEach
            runtime.inspect(entity)?.let { handle ->
                candidates += CleanupTarget.Furniture(handle.root.uniqueId, handle.family, handle.namespacedId)
            }
        }
        barriers
            .filter { it.world == world.name }
            .forEach { candidates += CleanupTarget.Barrier(it) }
        val center = CleanupCenter(world.name, anchor.blockX, anchor.blockY, anchor.blockZ)
        return execute(FurnitureCleanupPlan.create(center, 1, candidates), runtime)
    }

    private inline fun forEachBlockInSphere(
        world: World,
        center: Location,
        radius: Int,
        action: (Int, Int, Int) -> Unit,
    ) {
        val radiusSquared = radius.toDouble() * radius
        val minX = floor(center.x - radius).toInt()
        val maxX = ceil(center.x + radius).toInt()
        val minY = floor(center.y - radius).toInt().coerceAtLeast(world.minHeight)
        val maxY = ceil(center.y + radius).toInt().coerceAtMost(world.maxHeight - 1)
        val minZ = floor(center.z - radius).toInt()
        val maxZ = ceil(center.z + radius).toInt()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val dx = x + 0.5 - center.x
                    val dy = y + 0.5 - center.y
                    val dz = z + 0.5 - center.z
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) action(x, y, z)
                }
            }
        }
    }
}
