package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginWorkshopSleepShiftTest : FreeSpec({
    val tables = listOf("carpenter", "upholsterer", "assembler", "finisher")

    "only a settled sleeper opens a station and all four workers take one turn" {
        val shift = OriginWorkshopSleepShift(tables, 2400)
        val player = UUID.randomUUID()
        for (table in tables + tables.first()) {
            shift.table shouldBe table
            shift.ready shouldBe false
            shift.acquire(table, player) shouldBe false
            shift.resting(100)
            shift.rotationDue(2499) shouldBe false
            shift.rotationDue(2500) shouldBe true
            shift.leaveRest()
            shift.next()
        }
    }

    "an accepted game pins an expired shift and only its owner can release it" {
        val shift = OriginWorkshopSleepShift(tables, 2400)
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        shift.resting(0)
        shift.acquire("assembler", player) shouldBe false
        shift.acquire("carpenter", player) shouldBe true
        shift.acquire("carpenter", other) shouldBe false
        shift.acquire("carpenter", player) shouldBe false
        shift.rotationDue(10000) shouldBe false
        shouldThrow<IllegalStateException> { shift.leaveRest() }
        shift.release("assembler", player)
        shift.release("carpenter", other)
        shift.owns("carpenter", player) shouldBe true
        shift.release("carpenter", player)
        shift.rotationDue(10000) shouldBe true
        shift.leaveRest()
        shift.next()
        shift.table shouldBe "upholsterer"
        shift.owns("carpenter", player) shouldBe false
    }

    "despawn or reload invalidates the reservation even if the same worker returns" {
        val shift = OriginWorkshopSleepShift(tables, 2400)
        val player = UUID.randomUUID()
        shift.resting(0)
        shift.acquire("carpenter", player) shouldBe true
        shift.invalidate()
        shift.owns("carpenter", player) shouldBe false
        shift.resting(5000)
        shift.owns("carpenter", player) shouldBe false
        shift.rotationDue(5000) shouldBe false
        shift.acquire("carpenter", UUID.randomUUID()) shouldBe true
    }
})
