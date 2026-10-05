package ru.arc.contracts

/** Six rows: orders left, controls in the central column, native offered items right. */
internal object ContractDeskLayout {
    const val ROWS = 6
    const val ORDER_COLUMNS = 4
    const val ORDERS_PER_PAGE = ORDER_COLUMNS * (ROWS - 1)
    const val DEPOSIT_CAPACITY = 4 * ROWS

    data class Geometry(
        val rows: Int,
        val page: Int,
        val pageCount: Int,
        val visibleOrderRange: IntRange,
        val orderSlots: List<Int>,
        val depositSlots: List<Int>,
        val saleSlot: Int,
        val previousPage: Int?,
        val nextPage: Int?,
    )

    fun calculate(totalOrders: Int, requestedPage: Int = 0): Geometry {
        require(totalOrders >= 0) { "totalOrders must be non-negative" }
        val pageCount = if (totalOrders == 0) 1 else (totalOrders - 1) / ORDERS_PER_PAGE + 1
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val first = page * ORDERS_PER_PAGE
        val count = minOf(totalOrders - first, ORDERS_PER_PAGE)
        val slots = when (count) {
            1 -> listOf(19)
            2 -> listOf(19, 20)
            3 -> listOf(18, 19, 20)
            4 -> listOf(18, 19, 20, 21)
            5 -> listOf(10, 18, 19, 20, 28)
            else -> (0 until count).map { it / ORDER_COLUMNS * 9 + it % ORDER_COLUMNS }
        }
        return Geometry(
            ROWS, page, pageCount, first until first + count, slots,
            (0 until ROWS).flatMap { row -> (5..8).map { row * 9 + it } },
            saleSlot = 4,
            previousPage = if (pageCount > 1) 45 else null,
            nextPage = if (pageCount > 1) 48 else null,
        )
    }
}
