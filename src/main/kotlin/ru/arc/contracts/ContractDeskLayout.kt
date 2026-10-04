package ru.arc.contracts

/** Left: orders; middle: controls; right: the existing nine offered-item slots. */
internal object ContractDeskLayout {
    const val ORDER_COLUMNS = 5
    const val ORDERS_PER_PAGE = ORDER_COLUMNS * 6

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

    fun calculate(totalOrders: Int, requestedPage: Int = 0, fixedRows: Int? = null): Geometry {
        require(totalOrders >= 0) { "totalOrders must be non-negative" }
        require(fixedRows == null || fixedRows in 3..6) { "Chest height must be 3..6 rows" }
        val rows = fixedRows ?: maxOf(3, (minOf(totalOrders, ORDERS_PER_PAGE) + ORDER_COLUMNS - 1) / ORDER_COLUMNS)
        val capacity = rows * ORDER_COLUMNS
        val pageCount = if (totalOrders == 0) 1 else (totalOrders - 1) / capacity + 1
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val first = page * capacity
        val count = minOf(totalOrders - first, capacity)
        val middleRow = rows / 2
        val orderSlots = if (count in 1..5) {
            (0 until count).map { index -> middleRow * 9 + ((2 * index + 1) * ORDER_COLUMNS) / (2 * count) }
        } else {
            (0 until count).map { index -> index / ORDER_COLUMNS * 9 + index % ORDER_COLUMNS }
        }
        return Geometry(
            rows, page, pageCount, first until first + count, orderSlots,
            ((middleRow - 1)..(middleRow + 1)).flatMap { row -> (6..8).map { row * 9 + it } },
            saleSlot = middleRow * 9 + 5,
            previousPage = if (pageCount > 1) (middleRow - 1) * 9 + 5 else null,
            nextPage = if (pageCount > 1) (middleRow + 1) * 9 + 5 else null,
        )
    }
}
