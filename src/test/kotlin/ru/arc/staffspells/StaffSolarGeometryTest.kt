package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class StaffSolarGeometryTest : FreeSpec({
    "LANCE charges compactly, advances only its reached channel, then opens a contact flare" {
        fun parts(age: Int) = staffDisplayParts(
            StaffSpell.LANCE, age, staffLanceDuration(48.0), 48.0, 0.75, impact = true,
        )

        val charge = parts(0)
        val held = parts(4)
        val flight = parts(6)
        val arrival = parts(10)
        val burst = parts(12)

        listOf(charge, held, flight, arrival, burst).forEach { it.size shouldBe 48 }
        charge.map { it.material } shouldBe held.map { it.material }
        charge.map { it.material } shouldBe flight.map { it.material }
        charge.map { it.material } shouldBe arrival.map { it.material }
        charge.map { it.material } shouldBe burst.map { it.material }

        charge.take(40).none { it.visible } shouldBe true
        held.take(40).none { it.visible } shouldBe true
        charge.drop(40).take(8).all { it.visible } shouldBe true
        flight.take(16).any { it.visible } shouldBe true
        flight.drop(16).take(24).any { it.visible } shouldBe true
        arrival.take(40).all { it.visible } shouldBe true
        arrival[40].center.z shouldBe 48f
        abs(arrival[40].center.y) shouldBe 0f
        (abs(burst[41].center.x) > abs(arrival[41].center.x)) shouldBe true

        (abs(charge[40].center.z - 2f) < 0.001f) shouldBe true
        (abs(charge[40].center.y + 0.75f) < 0.001f) shouldBe true
        (charge[40].scale.x < 0.5f) shouldBe true

        (charge + held + flight + arrival + burst).all { part ->
            listOf(part.center.x, part.center.y, part.center.z,
                part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
        } shouldBe true
    }

    "shared LANCE clock uses a four tick charge and a bounded fast flight" {
        staffLanceFlightTicks(48.0) shouldBe 10
        staffLanceDuration(48.0) shouldBe 22
        staffLanceFront(0, 48.0) shouldBe 2.0
        staffLanceFront(4, 48.0) shouldBe 2.0
        (staffLanceFront(5, 48.0) > 2.0) shouldBe true
        staffLanceFront(staffLanceFlightTicks(48.0), 48.0) shouldBe 48.0

        staffLanceFlightTicks(24.0) shouldBe 8
        staffLanceDuration(24.0) shouldBe 20
        staffLanceFront(4, 24.0) shouldBe 2.0
        staffLanceFront(staffLanceFlightTicks(24.0), 24.0) shouldBe 24.0
        staffLanceFlightTicks(0.1) shouldBe 8
        staffLanceFront(staffLanceFlightTicks(0.1), 0.1) shouldBe 0.1
    }
})
