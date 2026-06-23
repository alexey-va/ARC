package ru.arc.common.chests

import com.jeff_media.customblockdata.CustomBlockData
import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.treasurechests.HuntFurnitureRegistry
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn

/**
 * Интерфейс для работы с кастомными сундуками.
 *
 * Определяет общий контракт для всех типов сундуков:
 * - vanilla Minecraft сундуки
 * - ItemsAdder кастомная мебель
 * - другие возможные реализации
 */
interface CustomChest {
    /** Блок, на котором размещён сундук */
    val block: Block

    /** Локация блока */
    val blockLocation: Location
        get() = block.location

    /** Создаёт/размещает сундук в мире */
    fun create(): Boolean

    /** Удаляет сундук из мира */
    fun destroy()
}

/**
 * Провайдер для работы с CustomBlockData.
 * Позволяет изолировать зависимость от CustomBlockData для тестирования.
 */
interface BlockDataProvider {
    fun setMarker(
        block: Block,
        key: NamespacedKey,
        value: String,
    )

    fun getMarker(
        block: Block,
        key: NamespacedKey,
    ): String?

    fun removeMarker(
        block: Block,
        key: NamespacedKey,
    )

    companion object {
        /**
         * Стандартная реализация с использованием CustomBlockData.
         */
        @JvmStatic
        val default: BlockDataProvider =
            object : BlockDataProvider {
                override fun setMarker(
                    block: Block,
                    key: NamespacedKey,
                    value: String,
                ) {
                    val data = CustomBlockData(block, ARC.instance)
                    data.set(key, PersistentDataType.STRING, value)
                }

                override fun getMarker(
                    block: Block,
                    key: NamespacedKey,
                ): String? {
                    val data = CustomBlockData(block, ARC.instance)
                    return data.get(key, PersistentDataType.STRING)
                }

                override fun removeMarker(
                    block: Block,
                    key: NamespacedKey,
                ) {
                    val data = CustomBlockData(block, ARC.instance)
                    data.remove(key)
                }
            }
    }
}

/**
 * Ключ для маркера кастомного сундука в CustomBlockData.
 * Thread-safe: использует @Volatile и double-checked locking.
 */
object ChestMarkerKey {
    @Volatile
    private var cachedKey: NamespacedKey? = null

    @JvmStatic
    fun get(): NamespacedKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            return NamespacedKey(ARC.instance, "custom_chest").also { cachedKey = it }
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(this) {
            cachedKey = null
        }
    }
}

object ChestEntityKey {
    @Volatile
    private var cachedKey: NamespacedKey? = null

    @JvmStatic
    fun get(): NamespacedKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            return NamespacedKey(ARC.instance, "custom_chest_entity").also { cachedKey = it }
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(this) {
            cachedKey = null
        }
    }
}

/**
 *
 * Размещает обычный сундук и маркирует его через CustomBlockData.
 *
 * @property block блок для размещения сундука
 * @property blockDataProvider провайдер для работы с CustomBlockData
 */
class VanillaChest(
    override val block: Block,
    private val blockDataProvider: BlockDataProvider = BlockDataProvider.default,
) : CustomChest {
    companion object {
        const val MARKER_VALUE = "vanilla"
    }

    override fun create(): Boolean {
        if (block.type != Material.AIR) {
            debug("Block at {} is not air! Not placing vanilla chest!", block.location)
            return false
        }

        block.type = Material.CHEST
        blockDataProvider.setMarker(block, ChestMarkerKey.get(), MARKER_VALUE)
        return true
    }

    override fun destroy() {
        blockDataProvider.removeMarker(block, ChestMarkerKey.get())

        if (block.type != Material.CHEST) {
            debug("Block at {} is not chest! Not removing!", block.location)
            return
        }

        block.type = Material.AIR
    }
}

/**
 * Провайдер для ItemsAdder CustomFurniture.
 * Позволяет изолировать зависимость от ItemsAdder API для тестирования.
 */
interface FurnitureProvider {
    fun spawn(
        namespaceId: String,
        block: Block,
    ): Any?

    fun getByBlock(block: Block): Any?

    /** Медленный fallback: скан entity рядом (только при destroy, не в hot path). */
    fun findNearEntities(block: Block): Any?

    fun getEntity(furniture: Any): Entity?

    fun remove(
        furniture: Any,
        dropItems: Boolean,
    )

    fun removeEntity(
        entity: Entity,
        dropItems: Boolean,
    )

    companion object {
        /**
         * Стандартная реализация с использованием ItemsAdder API.
         */
        @JvmStatic
        val default: FurnitureProvider =
            object : FurnitureProvider {
                override fun spawn(
                    namespaceId: String,
                    block: Block,
                ): Any? =
                    try {
                        CustomFurniture.spawn(namespaceId, block)
                    } catch (e: Exception) {
                        error("Failed to spawn ItemsAdder furniture: {}", namespaceId, e)
                        null
                    }

                override fun getByBlock(block: Block): Any? = ItemsAdderFurnitureLookup.findOnBlocks(block)

                override fun findNearEntities(block: Block): Any? = ItemsAdderFurnitureLookup.findNearEntities(block)

                override fun getEntity(furniture: Any): Entity? = (furniture as? CustomFurniture)?.entity

                override fun remove(
                    furniture: Any,
                    dropItems: Boolean,
                ) {
                    (furniture as? CustomFurniture)?.remove(dropItems)
                }

                override fun removeEntity(
                    entity: Entity,
                    dropItems: Boolean,
                ) {
                    try {
                        CustomFurniture.remove(entity, dropItems)
                    } catch (e: Exception) {
                        debug("Failed to remove ItemsAdder entity {}: {}", entity.uniqueId, e.message)
                    }
                }
            }
    }
}

/**
 * Поиск ItemsAdder-мебели по anchor block и соседям (без скана всех entity).
 */
internal object ItemsAdderFurnitureLookup {
    private val NEIGHBOR_OFFSETS =
        listOf(
            Triple(0, 0, 0),
            Triple(0, 1, 0),
            Triple(0, -1, 0),
            Triple(1, 0, 0),
            Triple(-1, 0, 0),
            Triple(0, 0, 1),
            Triple(0, 0, -1),
        )

    fun findOnBlocks(block: Block): Any? {
        for ((dx, dy, dz) in NEIGHBOR_OFFSETS) {
            findOnBlock(block.getRelative(dx, dy, dz))?.let { return it }
        }
        return null
    }

    /** Дорогой fallback — только если block lookup и UUID не помогли. */
    fun findNearEntities(block: Block): Any? {
        val center = block.location.add(0.5, 0.5, 0.5)
        val world = center.world ?: return null
        for (entity in world.getNearbyEntities(center, 2.5, 2.5, 2.5)) {
            findOnEntity(entity)?.let { return it }
        }
        return null
    }

    private fun findOnBlock(relative: Block): Any? =
        try {
            CustomFurniture.byAlreadySpawned(relative)
        } catch (e: Exception) {
            debug("Failed to lookup furniture on {}: {}", relative.location, e.message)
            null
        }

    private fun findOnEntity(entity: Entity): Any? =
        try {
            CustomFurniture.byAlreadySpawned(entity)
        } catch (_: Exception) {
            null
        }
}

/**
 * ItemsAdder кастомный сундук (мебель).
 *
 * Размещает кастомную мебель из ItemsAdder и маркирует блок через CustomBlockData.
 * При удалении также очищает окружающие barrier блоки и невидимые ItemFrame.
 *
 * @property block блок для размещения мебели
 * @property namespaceId ID мебели в ItemsAdder (например "namespace:furniture_id")
 * @property blockDataProvider провайдер для работы с CustomBlockData
 * @property furnitureProvider провайдер для работы с ItemsAdder
 */
class ItemsAdderChest(
    override val block: Block,
    val namespaceId: String,
    private val blockDataProvider: BlockDataProvider = BlockDataProvider.default,
    private val furnitureProvider: FurnitureProvider = FurnitureProvider.default,
    private val entityScanner: FurnitureEntityScanner = FurnitureEntityTracker,
) : CustomChest {
    companion object {
        const val MARKER_VALUE = "ia"
    }

    private var furniture: Any? = null

    override fun create(): Boolean {
        if (block.type != Material.AIR) {
            debug("Block at {} is not air! Not placing ItemsAdder chest!", block.location)
            return false
        }

        val beforeEntities = entityScanner.snapshotNear(block, FurnitureEntityTracker.SPAWN_SCAN_RADIUS)
        val beforeBarriers = FurnitureBarrierTracker.snapshotBarrierBlocks(block)
        furniture = furnitureProvider.spawn(namespaceId, block)
        if (furniture == null) {
            warn("Failed to spawn ItemsAdder furniture {} at {}", namespaceId, block.location)
            return false
        }

        val afterEntities = entityScanner.snapshotNear(block, FurnitureEntityTracker.SPAWN_SCAN_RADIUS)
        val afterBarriers = FurnitureBarrierTracker.snapshotBarrierBlocks(block)
        val spawnedEntities =
            FurnitureEntityTracker.mergeWithFurnitureEntity(
                FurnitureEntityTracker.detectSpawned(beforeEntities, afterEntities),
                furnitureProvider.getEntity(furniture!!),
            )
        val spawnedBarriers = FurnitureBarrierTracker.detectSpawned(beforeBarriers, afterBarriers)

        blockDataProvider.setMarker(block, ChestMarkerKey.get(), MARKER_VALUE)
        HuntFurnitureRegistry.register(block, spawnedEntities, spawnedBarriers)
        debug(
            "ItemsAdder chest {} at {} tracked {} entities, {} barriers in registry",
            namespaceId,
            block.location,
            spawnedEntities.size,
            spawnedBarriers.size,
        )
        return true
    }

    override fun destroy() {
        val anchor = HuntFurnitureRegistry.take(block)
        ItemsAdderFurnitureRemover.clearMarkers(block, blockDataProvider)
        val removed =
            ItemsAdderFurnitureRemover.removeAll(
                block,
                furniture,
                anchor,
                blockDataProvider,
                furnitureProvider,
            )
        furniture = null
        if (removed == 0 && anchor != null && anchor.entityIds.isNotEmpty()) {
            warn("Could not remove tracked entities at {}, visuals cleaned", block.location)
        }
    }
}

/**
 * Фабрика для создания CustomChest по типу.
 */
object ChestFactory {
    /**
     * Создаёт сундук нужного типа.
     *
     * @param block блок для размещения
     * @param type тип сундука ("vanilla" или "ia")
     * @param namespaceId ID для ItemsAdder (только для type="ia")
     * @return созданный сундук или null при ошибке
     */
    @JvmStatic
    fun create(
        block: Block,
        type: String,
        namespaceId: String? = null,
    ): CustomChest? {
        return when (type.lowercase()) {
            "vanilla" -> {
                VanillaChest(block)
            }

            "ia", "itemsadder", "items_adder" -> {
                if (namespaceId.isNullOrBlank()) {
                    error("ItemsAdder chest requires namespaceId")
                    return null
                }
                ItemsAdderChest(block, namespaceId)
            }

            else -> {
                error("Unknown chest type: {}", type)
                null
            }
        }
    }

    /**
     * Создаёт vanilla сундук.
     */
    @JvmStatic
    fun vanilla(block: Block): VanillaChest = VanillaChest(block)

    /**
     * Создаёт ItemsAdder сундук.
     */
    @JvmStatic
    fun itemsAdder(
        block: Block,
        namespaceId: String,
    ): ItemsAdderChest = ItemsAdderChest(block, namespaceId)
}
