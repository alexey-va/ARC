package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle
import kotlin.math.sqrt

class StaffRequiemGeometryTest : FreeSpec({
    "primary bell repeats three bounded radius-five fronts over its extended lifetime" {
        staffArsenalLifetime(StaffSpell.REQUIEM, false) shouldBe STAFF_REQUIEM_PRIMARY_DURATION_TICKS
        STAFF_REQUIEM_PRIMARY_CLANG_TICKS shouldBe listOf(16, 40, 64)
        staffRequiemPulse(15, secondary = false) shouldBe null
        staffRequiemPulse(16, secondary = false)?.progress shouldBe 0.0
        staffRequiemPulse(30, secondary = false)?.progress shouldBe 1.0
        staffRequiemPulse(32, secondary = false) shouldBe null

        val opening = staffRequiemBellParts(0, 36, secondary = false)
        val assembled = staffRequiemBellParts(8, 36, secondary = false)
        (opening.minOf { it.center.y } > assembled.minOf { it.center.y } + 1.0f) shouldBe true
        val parts = staffRequiemBellParts(10, 36, secondary = false)
        (parts.size in 1..48) shouldBe true
        parts.mapNotNull { it.identity }.distinct().size shouldBe parts.size
        parts.any { it.material == Material.COPPER_BLOCK } shouldBe true
        parts.any { it.material == Material.OXIDIZED_COPPER } shouldBe true
        parts.any { it.material == Material.GOLD_BLOCK } shouldBe true
        parts.any { it.material == Material.DEEPSLATE } shouldBe true
        parts.all { it.center.y in 3.0f..5.8f } shouldBe true
        (parts.maxOf { it.center.x + it.scale.x / 2 } -
            parts.minOf { it.center.x - it.scale.x / 2 } > 2.0f) shouldBe true
        (staffRequiemBellParts(34, 36, false).first().scale.x <
            staffRequiemBellParts(30, 36, false).first().scale.x) shouldBe true

        val front = staffRequiemParticles(30, 36, secondary = false, radius = STAFF_REQUIEM_PRIMARY_RADIUS)
        val maxRadius = front.maxOf { sqrt(it.offset.x * it.offset.x + it.offset.z * it.offset.z) }
        (maxRadius in 4.7..5.001) shouldBe true
        staffRequiemParticles(36, 36, secondary = false, radius = 5.0).isEmpty() shouldBe true
    }

    "secondary bell schedules five bounded radius-7 soul fronts with no smoke sprites" {
        staffArsenalLifetime(StaffSpell.REQUIEM, true) shouldBe STAFF_REQUIEM_SECONDARY_DURATION_TICKS
        STAFF_REQUIEM_SECONDARY_CLANG_TICKS shouldBe listOf(16, 40, 64, 88, 112)
        listOf(16, 40, 64, 88, 112).forEachIndexed { index, tick ->
            staffRequiemPulse(tick, secondary = true)?.index shouldBe index
            staffRequiemPulse(tick + STAFF_REQUIEM_SECONDARY_FRONT_TICKS, secondary = true)?.progress shouldBe 1.0
            staffRequiemPulse(tick + STAFF_REQUIEM_SECONDARY_FRONT_TICKS + 2, secondary = true) shouldBe null
        }
        STAFF_REQUIEM_COLLISION_RAY_COUNT shouldBe 24

        val idle = staffRequiemParticles(8, 134, secondary = true, radius = 7.0)
        val front = staffRequiemParticles(30, 134, secondary = true, radius = STAFF_REQUIEM_SECONDARY_RADIUS)
        idle.size shouldBe 40
        front.size shouldBe 56
        val maxRadius = front.maxOf { sqrt(it.offset.x * it.offset.x + it.offset.z * it.offset.z) }
        (maxRadius in 6.5..7.001) shouldBe true
        val allowed = setOf(Particle.SOUL, Particle.SOUL_FIRE_FLAME, Particle.DUST_COLOR_TRANSITION)
        (idle + front).all { it.particle in allowed } shouldBe true
        val clipped = staffRequiemParticles(30, 134, secondary = true, radius = 7.0,
            rayLimits = List(STAFF_REQUIEM_COLLISION_RAY_COUNT) { if (it == 0) 2.0 else 7.0 })
        clipped.take(2).all { sqrt(it.offset.x * it.offset.x + it.offset.z * it.offset.z) <= 2.001 } shouldBe true
        staffRequiemBellParts(134, 134, secondary = true).isEmpty() shouldBe true
    }
})
