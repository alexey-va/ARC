package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StaffChargeGeometryTest : FreeSpec({
    "all spell families assemble fixed charge slots into distinct formations" {
        val silhouettes = mutableSetOf<List<Triple<Float, Float, Float>>>()
        StaffSpell.entries.forEach { spell ->
            val empty = staffChargeParts(spell, 0.0, 0)
            val half = staffChargeParts(spell, 0.5, 18)
            val full = staffChargeParts(spell, 1.0, 36)

            full.size shouldBe (if (spell == StaffSpell.ICICLE) 33 else 21)
            empty.size shouldBe full.size
            empty.map { it.material } shouldBe full.map { it.material }
            empty.map { it.visible } shouldBe full.map { it.visible }
            empty.all { it.visible } shouldBe true
            (empty.sumOf { it.scale.length().toDouble() } < half.sumOf { it.scale.length().toDouble() }) shouldBe true
            (half.sumOf { it.scale.length().toDouble() } < full.sumOf { it.scale.length().toDouble() }) shouldBe true
            full.all { it.center.length() - it.scale.length() / 2 > 1.15f } shouldBe true
            silhouettes += full.map { Triple(it.center.x, it.center.y, it.center.z) }
        }
        silhouettes.size shouldBe StaffSpell.entries.size
    }

    "full charge enlarges the localized projectile core as well as its wake" {
        listOf(StaffSpell.EMBER, StaffSpell.MARK).forEach { spell ->
            fun parts(charge: Double) = staffDisplayParts(spell, ageTicks = 10, durationTicks = 20,
                length = 12.0, radius = 3.0, impact = false, charge = charge)
            val weak = parts(0.0).first()
            val full = parts(1.0).first()
            full.center shouldBe weak.center
            (full.scale.x >= weak.scale.x * 1.39f) shouldBe true
            (full.scale.y >= weak.scale.y * 1.39f) shouldBe true
        }
    }

    "released legacy spell support grows with charge without moving attack geometry" {
        val legacy = listOf(StaffSpell.CHAIN, StaffSpell.MARK, StaffSpell.FROST,
            StaffSpell.ICICLE, StaffSpell.FAULT, StaffSpell.LANCE, StaffSpell.EMBER)
        legacy.forEach { spell ->
            fun parts(charge: Double) = staffDisplayParts(spell, ageTicks = 20, durationTicks = 48,
                length = 18.0, radius = 3.5, impact = false, charge = charge)

            val empty = parts(0.0)
            val half = parts(0.5)
            val full = parts(1.0)
            empty.size shouldBe full.size
            empty.map { it.material } shouldBe full.map { it.material }
            empty.map { it.center } shouldBe half.map { it.center }
            half.map { it.center } shouldBe full.map { it.center }
            empty.map { it.visible } shouldBe full.map { it.visible }
            (empty.sumOf { it.scale.length().toDouble() } < half.sumOf { it.scale.length().toDouble() }) shouldBe true
            (half.sumOf { it.scale.length().toDouble() } < full.sumOf { it.scale.length().toDouble() }) shouldBe true
        }
    }
})
