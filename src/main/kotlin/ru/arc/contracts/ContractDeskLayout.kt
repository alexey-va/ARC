package ru.arc.contracts

/** Pure slot geometry for the paged NPC contract chest. Physical slots are zero-based. */
internal object ContractDeskLayout {
    const val ORDERS_PER_PAGE = 36

    private const val SLOTS_PER_ROW = 9
    private const val FIXED_ROWS = 2

    data class Geometry(
        val rows: Int,
        val page: Int,
        val pageCount: Int,
        /** Zero-based order indices visible on this page; empty when there are no orders. */
        val visibleOrderRange: IntRange,
        /** Physical order slots in the same order as [visibleOrderRange]. */
        val orderSlots: List<Int>,
        /** All nine physical slots in the deposit row. */
        val depositSlots: List<Int>,
        val footerSaleCenter: Int,
        val infoLeft: Int,
        val previousPage: Int?,
        val nextPage: Int?,
    )

    fun calculate(totalOrders: Int, requestedPage: Int = 0): Geometry {
        require(totalOrders >= 0) { "totalOrders must be non-negative" }

        val pageCount = if (totalOrders == 0) 1 else (totalOrders - 1) / ORDERS_PER_PAGE + 1
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val firstOrder = page * ORDERS_PER_PAGE
        val visibleCount = minOf(totalOrders - firstOrder, ORDERS_PER_PAGE)
        val visibleOrderRange = firstOrder until firstOrder + visibleCount

        // Keep all pages the same height, including a short final page.
        val orderRows = maxOf(1, (minOf(totalOrders, ORDERS_PER_PAGE) + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW)
        val rows = orderRows + FIXED_ROWS
        val slotsPerOrderRow = visibleCount / orderRows
        val extraOrders = visibleCount % orderRows
        val orderSlots = buildList(visibleCount) {
            repeat(orderRows) { row ->
                val count = slotsPerOrderRow + if (row < extraOrders) 1 else 0
                val rowStart = row * SLOTS_PER_ROW
                centeredColumns(count).forEach { column -> add(rowStart + column) }
            }
        }

        val depositRowStart = (rows - 2) * SLOTS_PER_ROW
        val footerRowStart = (rows - 1) * SLOTS_PER_ROW
        val paginated = pageCount > 1

        return Geometry(
            rows = rows,
            page = page,
            pageCount = pageCount,
            visibleOrderRange = visibleOrderRange,
            orderSlots = orderSlots,
            depositSlots = (depositRowStart until depositRowStart + SLOTS_PER_ROW).toList(),
            footerSaleCenter = footerRowStart + 4,
            infoLeft = footerRowStart,
            previousPage = if (paginated) footerRowStart + 3 else null,
            nextPage = if (paginated) footerRowStart + 5 else null,
        )
    }

    private fun centeredColumns(count: Int): List<Int> =
        (0 until count).map { index -> ((2 * index + 1) * SLOTS_PER_ROW) / (2 * count) }
}
