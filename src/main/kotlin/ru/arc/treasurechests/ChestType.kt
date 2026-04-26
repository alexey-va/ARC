package ru.arc.treasurechests

import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures

/**
 * Тип сундука в охоте за сокровищами.
 *
 * @property type тип сундука (Vanilla или ItemsAdder)
 * @property treasurePoolId ID пула сокровищ для выдачи награды
 * @property particlePath путь к настройкам партиклов в конфиге
 * @property namespaceId ID для ItemsAdder (nullable для Vanilla)
 * @property weight вес для взвешенного случайного выбора
 */
data class ChestType(
    val type: ChestVariant,
    val treasurePoolId: String,
    val particlePath: String = "default",
    val namespaceId: String? = null,
    val weight: Int = 1,
) {
    /**
     * Получает TreasurePool по ID.
     * @return TreasurePool или null если не найден
     */
    fun getTreasurePool(): TreasurePool? = Treasures.getPool(treasurePoolId)

    companion object {
        /**
         * Создаёт Vanilla сундук.
         */
        fun vanilla(
            treasurePoolId: String,
            particlePath: String = "default",
            weight: Int = 1,
        ) = ChestType(
            type = ChestVariant.VANILLA,
            treasurePoolId = treasurePoolId,
            particlePath = particlePath,
            weight = weight,
        )

        /**
         * Создаёт ItemsAdder сундук.
         */
        fun itemsAdder(
            namespaceId: String,
            treasurePoolId: String,
            particlePath: String = "default",
            weight: Int = 1,
        ) = ChestType(
            type = ChestVariant.ITEMS_ADDER,
            treasurePoolId = treasurePoolId,
            particlePath = particlePath,
            namespaceId = namespaceId,
            weight = weight,
        )
    }
}

/**
 * Вариант сундука.
 */
enum class ChestVariant {
    /** Стандартный Minecraft сундук */
    VANILLA,

    /** Кастомный сундук из ItemsAdder */
    ITEMS_ADDER,

    ;

    companion object {
        /**
         * Парсит строку в ChestVariant.
         * Поддерживает legacy значения: "IA" → ITEMS_ADDER
         */
        fun fromString(value: String): ChestVariant =
            when (value.uppercase()) {
                "VANILLA" -> VANILLA
                "IA", "ITEMS_ADDER", "ITEMSADDER" -> ITEMS_ADDER
                else -> throw IllegalArgumentException("Unknown chest variant: $value")
            }
    }
}
