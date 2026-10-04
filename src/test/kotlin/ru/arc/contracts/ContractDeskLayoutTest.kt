package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ContractDeskLayoutTest : StringSpec({
    "only sparse lists up to five orders are centered symmetrically on the left" {
        for (count in 1..5) {
            val layout = ContractDeskLayout.calculate(count)
            val columns = layout.orderSlots.map { it % 9 }
            columns.map { 4 - it }.sorted() shouldBe columns
            layout.orderSlots.map { it / 9 }.distinct() shouldBe listOf(layout.rows / 2)
        }
    }

    "larger lists fill every left column consecutively without decorative gaps" {
        for (count in 6..30) {
            val layout = ContractDeskLayout.calculate(count)
            layout.orderSlots shouldBe (0 until count).map { it / 5 * 9 + it % 5 }
        }
        ContractDeskLayout.calculate(21).rows shouldBe 5
        ContractDeskLayout.calculate(21).orderSlots shouldBe listOf(
            0,1,2,3,4,9,10,11,12,13,18,19,20,21,22,27,28,29,30,31,36,
        )
    }

    "keeps input in a separate nine-slot square and controls in the middle column" {
        for (count in 0..80) {
            val layout = ContractDeskLayout.calculate(count)
            val middle = layout.rows / 2
            layout.depositSlots shouldBe ((middle - 1)..(middle + 1)).flatMap { row -> (6..8).map { row * 9 + it } }
            layout.saleSlot shouldBe middle * 9 + 5
            val all = layout.orderSlots + layout.depositSlots + listOfNotNull(layout.saleSlot, layout.previousPage, layout.nextPage)
            all.distinct().size shouldBe all.size
            all.all { it in 0 until layout.rows * 9 } shouldBe true
            layout.orderSlots.all { it % 9 < 5 } shouldBe true
            layout.depositSlots.all { it % 9 >= 6 } shouldBe true
        }
        val empty = ContractDeskLayout.calculate(0)
        empty.rows shouldBe 3
        empty.visibleOrderRange.isEmpty() shouldBe true
        empty.orderSlots shouldBe emptyList()
        empty.depositSlots shouldBe listOf(6,7,8,15,16,17,24,25,26)
        empty.saleSlot shouldBe 14
        empty.previousPage.shouldBeNull()
        empty.nextPage.shouldBeNull()
    }

    "pagination starts after thirty and keeps input and controls stable on short pages" {
        ContractDeskLayout.calculate(30).pageCount shouldBe 1
        val first = ContractDeskLayout.calculate(31, -10)
        val last = ContractDeskLayout.calculate(31, 40)
        first.page shouldBe 0
        first.pageCount shouldBe 2
        first.visibleOrderRange shouldBe (0..29)
        last.visibleOrderRange shouldBe (30..30)
        last.orderSlots shouldBe listOf(29)
        last.rows shouldBe first.rows
        last.depositSlots shouldBe first.depositSlots
        last.saleSlot shouldBe first.saleSlot
        last.previousPage shouldBe first.previousPage
        last.nextPage shouldBe first.nextPage
    }

    "an open chest uses its fixed capacity when the catalog changes between rotations" {
        val first = ContractDeskLayout.calculate(40, fixedRows = 3)
        val last = ContractDeskLayout.calculate(40, 2, fixedRows = 3)
        first.pageCount shouldBe 3
        first.visibleOrderRange shouldBe (0..14)
        last.visibleOrderRange shouldBe (30..39)
        last.orderSlots.size shouldBe 10
        last.depositSlots shouldBe first.depositSlots
        last.saleSlot shouldBe first.saleSlot
    }
})
