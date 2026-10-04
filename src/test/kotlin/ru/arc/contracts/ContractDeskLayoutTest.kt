package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ContractDeskLayoutTest : StringSpec({
    "sparse order rows are symmetric for every occupancy from zero through nine" {
        for (count in 0..9) {
            val slots = ContractDeskLayout.calculate(count).orderSlots
            val columns = slots.map { it % 9 }

            columns.size shouldBe count
            columns shouldBe columns.sorted()
            columns.map { 8 - it }.sorted() shouldBe columns
        }
    }

    "chooses the required height and balances orders across order rows" {
        val expectedRows = mapOf(
            0 to 3,
            1 to 3,
            9 to 3,
            10 to 4,
            18 to 4,
            19 to 5,
            27 to 5,
            28 to 6,
            36 to 6,
            37 to 6,
        )

        expectedRows.forEach { (count, rows) ->
            ContractDeskLayout.calculate(count).rows shouldBe rows
        }

        val ten = ContractDeskLayout.calculate(10)
        ten.orderSlots.map { it / 9 }.groupingBy { it }.eachCount().values.sorted() shouldBe listOf(5, 5)

        val twentyOne = ContractDeskLayout.calculate(21)
        twentyOne.orderSlots.map { it / 9 }.groupingBy { it }.eachCount().values.sorted() shouldBe listOf(7, 7, 7)
    }

    "empty geometry keeps a usable height and has no page controls" {
        val layout = ContractDeskLayout.calculate(0)

        layout.rows shouldBe 3
        layout.page shouldBe 0
        layout.pageCount shouldBe 1
        layout.visibleOrderRange.isEmpty() shouldBe true
        layout.orderSlots shouldBe emptyList()
        layout.depositSlots shouldBe (9 until 18).toList()
        layout.footerSaleCenter shouldBe 22
        layout.infoLeft shouldBe 18
        layout.previousPage.shouldBeNull()
        layout.nextPage.shouldBeNull()
    }

    "pagination starts only after 36 orders, clamps the requested page, and keeps height stable" {
        val full = ContractDeskLayout.calculate(36)
        full.pageCount shouldBe 1
        full.previousPage.shouldBeNull()
        full.nextPage.shouldBeNull()

        val first = ContractDeskLayout.calculate(37, requestedPage = -4)
        first.page shouldBe 0
        first.pageCount shouldBe 2
        first.visibleOrderRange shouldBe (0..35)
        first.orderSlots.size shouldBe 36
        first.rows shouldBe 6
        first.infoLeft shouldBe 45
        first.previousPage shouldBe 48
        first.footerSaleCenter shouldBe 49
        first.nextPage shouldBe 50

        val last = ContractDeskLayout.calculate(37, requestedPage = 40)
        last.page shouldBe 1
        last.visibleOrderRange shouldBe (36..36)
        last.orderSlots.size shouldBe 1
        last.rows shouldBe first.rows
        last.orderSlots.single() % 9 shouldBe 4
    }

    "representative layouts keep every returned slot unique and in range" {
        for ((count, page) in listOf(3 to 0, 10 to 0, 21 to 0, 36 to 0, 37 to 0, 37 to 1)) {
            val layout = ContractDeskLayout.calculate(count, requestedPage = page)
            val footerSlots = listOfNotNull(
                layout.footerSaleCenter,
                layout.infoLeft,
                layout.previousPage,
                layout.nextPage,
            )
            val allSlots = layout.orderSlots + layout.depositSlots + footerSlots
            val inventorySize = layout.rows * 9
            val visibleCount =
                minOf(count - page * ContractDeskLayout.ORDERS_PER_PAGE, ContractDeskLayout.ORDERS_PER_PAGE)

            layout.visibleOrderRange.count() shouldBe visibleCount
            layout.orderSlots.size shouldBe visibleCount
            layout.depositSlots.size shouldBe 9
            allSlots.distinct().size shouldBe allSlots.size
            allSlots.all { it in 0 until inventorySize } shouldBe true
            layout.depositSlots shouldBe (layout.depositSlots.first()..layout.depositSlots.last()).toList()

            val rowOccupancies = layout.orderSlots.map { it / 9 }.groupingBy { it }.eachCount().values
            if (rowOccupancies.isNotEmpty()) {
                val spread = rowOccupancies.maxOrNull()!! - rowOccupancies.minOrNull()!!
                val balanced = spread <= 1
                balanced shouldBe true
            }
        }
    }

    "total order indices map one-to-one to physical order slots" {
        val layout = ContractDeskLayout.calculate(21)

        layout.visibleOrderRange.count() shouldBe layout.orderSlots.size
        layout.orderSlots.zip(layout.visibleOrderRange).map { (slot, _) -> slot }.distinct().size shouldBe 21
    }
})
