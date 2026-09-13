package ru.arc.treasure.core

import java.util.concurrent.ThreadLocalRandom

/** Bounded number of independent rewards issued by one physical treasure item. */
internal data class TreasureRollRange(
    val minimum: Int,
    val maximum: Int,
) {
    init {
        require(minimum in 1..MAX_ROLLS)
        require(maximum in minimum..MAX_ROLLS)
    }

    fun roll(): Int = roll { origin, bound -> ThreadLocalRandom.current().nextInt(origin, bound) }

    internal fun roll(nextInt: (origin: Int, bound: Int) -> Int): Int =
        if (minimum == maximum) minimum else nextInt(minimum, maximum + 1)

    companion object {
        private const val MAX_ROLLS = 64

        fun resolve(configuredMinimum: Int?, configuredMaximum: Int?): TreasureRollRange {
            val minimum = (configuredMinimum ?: 1).coerceIn(1, MAX_ROLLS)
            val maximum = (configuredMaximum ?: minimum).coerceIn(minimum, MAX_ROLLS)
            return TreasureRollRange(minimum, maximum)
        }
    }
}
