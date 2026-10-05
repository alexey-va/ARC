package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ContractDeskLayoutTest : StringSpec({
    "all desks keep six rows with centered sparse orders and dense larger lists" {
        for (count in 0..80) ContractDeskLayout.calculate(count).rows shouldBe 6
        ContractDeskLayout.calculate(1).orderSlots shouldBe listOf(19)
        ContractDeskLayout.calculate(2).orderSlots shouldBe listOf(19, 20)
        ContractDeskLayout.calculate(3).orderSlots shouldBe listOf(18, 19, 20)
        ContractDeskLayout.calculate(4).orderSlots shouldBe listOf(18, 19, 20, 21)
        ContractDeskLayout.calculate(5).orderSlots shouldBe listOf(10, 18, 19, 20, 28)
        for (count in 6..20) ContractDeskLayout.calculate(count).orderSlots shouldBe
            (0 until count).map { it / 4 * 9 + it % 4 }
    }
    "input fills the right four columns and sale is at the top of the central column" {
        for (count in 0..80) {
            val layout = ContractDeskLayout.calculate(count)
            layout.depositSlots shouldBe (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
            layout.saleSlot shouldBe 4
            val all = layout.orderSlots + layout.depositSlots + listOfNotNull(layout.saleSlot, layout.previousPage, layout.nextPage)
            all.distinct().size shouldBe all.size
            all.all { it in 0 until 54 } shouldBe true
            layout.orderSlots.all { it % 9 < 4 && it / 9 < 5 } shouldBe true
        }
    }
    "pagination reserves the left bottom corners and keeps deposit slots stable" {
        val single = ContractDeskLayout.calculate(20)
        single.pageCount shouldBe 1
        single.previousPage.shouldBeNull()
        single.nextPage.shouldBeNull()
        val first = ContractDeskLayout.calculate(21, -10)
        val last = ContractDeskLayout.calculate(21, 40)
        first.visibleOrderRange shouldBe (0..19)
        last.visibleOrderRange shouldBe (20..20)
        last.orderSlots shouldBe listOf(19)
        first.previousPage shouldBe 45
        first.nextPage shouldBe 48
        last.depositSlots shouldBe first.depositSlots
        last.saleSlot shouldBe first.saleSlot
        ContractDeskLayout.calculate(0).visibleOrderRange.isEmpty() shouldBe true
    }
})
