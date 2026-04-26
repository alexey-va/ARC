package ru.arc.common.chests

import com.jeff_media.customblockdata.CustomBlockData
import dev.lone.itemsadder.api.CustomFurniture
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn
import ru.arc.util.cleanupCustomItemFrames
import ru.arc.util.cleanupDisplayEntities

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

/**
 * Vanilla Minecraft сундук.
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

    fun remove(
        furniture: Any,
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

                override fun getByBlock(block: Block): Any? =
                    try {
                        CustomFurniture.byAlreadySpawned(block)
                    } catch (e: Exception) {
                        debug("Failed to get furniture at {}: {}", block.location, e.message)
                        null
                    }

                override fun remove(
                    furniture: Any,
                    dropItems: Boolean,
                ) {
                    (furniture as? CustomFurniture)?.remove(dropItems)
                }
            }
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
) : CustomChest {
    companion object {
        const val MARKER_VALUE = "ia"

        /** Радиус поиска ItemFrame для очистки */
        private const val FRAME_SEARCH_RADIUS = 1.5
    }

    private var furniture: Any? = null

    override fun create(): Boolean {
        if (block.type != Material.AIR) {
            debug("Block at {} is not air! Not placing ItemsAdder chest!", block.location)
            return false
        }

        furniture = furnitureProvider.spawn(namespaceId, block)
        if (furniture == null) {
            warn("Failed to spawn ItemsAdder furniture {} at {}", namespaceId, block.location)
            return false
        }

        blockDataProvider.setMarker(block, ChestMarkerKey.get(), MARKER_VALUE)
        return true
    }

    override fun destroy() {
        val key = ChestMarkerKey.get()
        val marker = blockDataProvider.getMarker(block, key)

        if (marker != MARKER_VALUE) {
            error("Block at {} is not ItemsAdderChest (marker={})! Not removing!", block.location, marker)
            return
        }

        blockDataProvider.removeMarker(block, key)

        // Получаем мебель если ещё не сохранена
        val currentFurniture = furniture ?: furnitureProvider.getByBlock(block)

        if (currentFurniture == null) {
            debug("No furniture at block {}", block.location)
        } else {
            furnitureProvider.remove(currentFurniture, false)
        }

        // Очищаем окружающие блоки и entities
        cleanup()
    }

    /**
     * Очищает окружающие barrier блоки, невидимые ItemFrame и Display entities,
     * которые могут остаться после удаления ItemsAdder мебели.
     */
    private fun cleanup() {
        try {
            cleanupItemFrames()
            cleanupDisplayEntities()
            cleanupBarrierBlocks()
        } catch (e: Exception) {
            error("Error cleaning up ItemsAdder chest at {}", block.location, e)
        }
    }

    private fun cleanupItemFrames() {
        block.location.cleanupCustomItemFrames(FRAME_SEARCH_RADIUS)
    }

    private fun cleanupDisplayEntities() {
        block.location.cleanupDisplayEntities(FRAME_SEARCH_RADIUS)
    }

    private fun cleanupBarrierBlocks() {
        val blocksToCheck =
            listOf(
                block,
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0),
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1),
            )

        for (b in blocksToCheck) {
            if (b.type == Material.BARRIER) {
                b.type = Material.AIR
            }
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
