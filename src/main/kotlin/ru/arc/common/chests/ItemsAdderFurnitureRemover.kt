package ru.arc.common.chests

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import ru.arc.treasurechests.HuntFurnitureAnchor
import ru.arc.treasurechests.HuntFurnitureRegistry
import ru.arc.util.Logging.debug
import ru.arc.worldcontent.BlockPosition
import ru.arc.worldcontent.FurnitureCleanupService
import ru.arc.worldcontent.ItemsAdderFurnitureRuntime
import java.util.UUID

/**
 * Удаление IA-мебели охоты через общее cleanup-ядро: сохранённые UUID и точные barrier-позиции.
 */
object ItemsAdderFurnitureRemover {
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
        return removeThroughSharedCleanup(
            block = block,
            entityIds = storedEntityIds,
            storedBarriers = anchor?.barrierBlocks.orEmpty(),
            cachedFurniture = cachedFurniture,
            furnitureProvider = furnitureProvider,
        )
    }

    /** Полная очистка записи из JSON-реестра. */
    fun removeAnchor(anchor: HuntFurnitureAnchor): Int {
        val world = Bukkit.getWorld(anchor.world)
        val block = anchor.anchorBlock(world)
        if (block != null) {
            val removed =
                removeThroughSharedCleanup(
                    block = block,
                    entityIds = anchor.entityUuids(),
                    storedBarriers = anchor.barrierBlocks,
                    cachedFurniture = null,
                    furnitureProvider = FurnitureProvider.default,
                )
            clearMarkers(block)
            return removed
        } else {
            cleanupBarrierBlocks(world, anchor.barrierBlocks)
        }
        return 0
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

    private fun removeThroughSharedCleanup(
        block: Block,
        entityIds: Collection<UUID>,
        storedBarriers: Collection<BlockPos>,
        cachedFurniture: Any?,
        furnitureProvider: FurnitureProvider,
    ): Int {
        val exactEntityIds = entityIds.toMutableSet()
        var resolvedFurniture = cachedFurniture
        cachedFurniture?.let(furnitureProvider::getEntity)?.takeIf { it.isValid }?.let { exactEntityIds += it.uniqueId }
        if (exactEntityIds.isEmpty()) {
            resolvedFurniture = resolveFurniture(null, block, furnitureProvider)
            resolvedFurniture
                ?.let { resolved ->
                    when (resolved) {
                        is Entity -> resolved
                        else -> furnitureProvider.getEntity(resolved)?.takeIf { it.isValid }
                    }
                }?.let { exactEntityIds += it.uniqueId }
        }

        if (furnitureProvider !== FurnitureProvider.default || !ItemsAdderFurnitureRuntime.available) {
            val removed =
                if (exactEntityIds.isNotEmpty()) {
                    removeTrackedEntities(exactEntityIds.toList(), furnitureProvider)
                } else {
                    removeViaFurnitureApi(resolvedFurniture, block, furnitureProvider)
                }
            cleanupBarrierBlocks(block.world, storedBarriers.toList())
            return removed
        }

        val barriers =
            storedBarriers.map { pos ->
                BlockPosition(block.world.name, pos.x, pos.y, pos.z)
            }
        return FurnitureCleanupService
            .executeKnown(block.location, exactEntityIds, barriers)
            .removedFurniture
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
