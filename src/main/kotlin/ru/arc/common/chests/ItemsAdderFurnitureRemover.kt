package ru.arc.common.chests

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import ru.arc.treasurechests.HuntFurnitureAnchor
import ru.arc.treasurechests.HuntFurnitureRegistry
import ru.arc.util.Logging.debug
import ru.arc.util.cleanupCustomItemFrames
import ru.arc.util.cleanupDisplayEntities
import java.util.UUID

/**
 * Удаление IA-мебели охоты: по сохранённым UUID, fallback через IA API, barrier/frame cleanup.
 */
object ItemsAdderFurnitureRemover {
    private const val FRAME_SEARCH_RADIUS = 2.5

    /**
     * @return число удалённых entity
     */
    fun removeAll(
        block: Block,
        cachedFurniture: Any? = null,
        anchor: HuntFurnitureAnchor? = null,
        blockDataProvider: BlockDataProvider = BlockDataProvider.default,
        furnitureProvider: FurnitureProvider = FurnitureProvider.default,
    ): Int {
        val storedEntityIds = anchor?.entityUuids() ?: HuntFurnitureRegistry.entityIdsAt(block)
        var removed = removeTrackedEntities(storedEntityIds, furnitureProvider)

        if (removed == 0) {
            removed = removeViaFurnitureApi(cachedFurniture, block, furnitureProvider)
        }

        cleanupVisuals(block, anchor?.barrierBlocks.orEmpty())
        return removed
    }

    /** Полная очистка записи из JSON-реестра. */
    fun removeAnchor(anchor: HuntFurnitureAnchor): Int {
        val world = Bukkit.getWorld(anchor.world)
        val block = anchor.anchorBlock(world)
        val removed = removeTrackedEntities(anchor.entityUuids(), FurnitureProvider.default)
        if (block != null) {
            if (removed == 0) {
                removeViaFurnitureApi(null, block, FurnitureProvider.default)
            }
            cleanupVisuals(block, anchor.barrierBlocks)
            clearMarkers(block)
        } else {
            cleanupBarrierBlocks(world, anchor.barrierBlocks)
        }
        return removed
    }

    fun clearMarkers(
        block: Block,
        blockDataProvider: BlockDataProvider = BlockDataProvider.default,
    ) {
        blockDataProvider.removeMarker(block, ChestMarkerKey.get())
    }

    fun removeEntityIds(
        entityIds: List<UUID>,
        furnitureProvider: FurnitureProvider = FurnitureProvider.default,
    ): Int = removeTrackedEntities(entityIds, furnitureProvider)

    /**
     * Осиротевший маркер (после краша/рестарта или неполного stop).
     * @return 1 если блок был IA-сундуком и обработан, иначе 0
     */
    fun cleanupOrphan(
        block: Block,
        blockDataProvider: BlockDataProvider = BlockDataProvider.default,
        furnitureProvider: FurnitureProvider = FurnitureProvider.default,
    ): Int {
        val fromRegistry = HuntFurnitureRegistry.take(block)
        val hasMarker = blockDataProvider.getMarker(block, ChestMarkerKey.get()) == ItemsAdderChest.MARKER_VALUE
        if (fromRegistry == null && !hasMarker) return 0

        val removed = removeAll(block, null, fromRegistry, blockDataProvider, furnitureProvider)
        clearMarkers(block, blockDataProvider)
        debug("[hunt-furniture] orphan cleanup at {} removed {} entities", block.location, removed)
        return 1
    }

    private fun removeTrackedEntities(
        entityIds: List<UUID>,
        furnitureProvider: FurnitureProvider,
    ): Int {
        var removed = 0
        for (uuid in entityIds) {
            val entity = Bukkit.getEntity(uuid) ?: continue
            furnitureProvider.removeEntity(entity, false)
            if (entity.isDead) {
                removed++
            } else {
                entity.remove()
                removed++
            }
        }
        return removed
    }

    private fun removeViaFurnitureApi(
        cachedFurniture: Any?,
        block: Block,
        furnitureProvider: FurnitureProvider,
    ): Int {
        when (val resolved = resolveFurniture(cachedFurniture, block, furnitureProvider)) {
            null -> {
                return 0
            }

            is Entity -> {
                furnitureProvider.removeEntity(resolved, false)
                return 1
            }

            else -> {
                furnitureProvider.remove(resolved, false)
                return 1
            }
        }
    }

    private fun resolveFurniture(
        cachedFurniture: Any?,
        block: Block,
        furnitureProvider: FurnitureProvider,
    ): Any? {
        cachedFurniture?.let { return it }
        furnitureProvider.getByBlock(block)?.let { return it }
        return furnitureProvider.findNearEntities(block)
    }

    fun cleanupVisuals(
        block: Block,
        storedBarriers: List<BlockPos> = emptyList(),
    ) {
        block.location.cleanupCustomItemFrames(FRAME_SEARCH_RADIUS)
        block.location.cleanupDisplayEntities(FRAME_SEARCH_RADIUS)
        cleanupBarrierBlocks(block.world, storedBarriers)
        cleanupBarrierNeighbors(block)
    }

    private fun cleanupBarrierNeighbors(block: Block) {
        val neighbors =
            listOf(
                block,
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0),
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1),
            )
        for (b in neighbors) {
            if (b.type == Material.BARRIER) {
                b.type = Material.AIR
            }
        }
    }

    private fun cleanupBarrierBlocks(
        world: World?,
        storedBarriers: List<BlockPos>,
    ) {
        val w = world ?: return
        for (pos in storedBarriers) {
            val b = w.getBlockAt(pos.x, pos.y, pos.z)
            if (b.type == Material.BARRIER) {
                b.type = Material.AIR
            }
        }
    }
}
