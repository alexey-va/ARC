package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

class StaffPreparationGeometryTest : FreeSpec({
    "preparation parts keep stable identities, finite bounds, and caster clearance" {
        val expectedEmpty = setOf(
            StaffSpell.EMBER to false,
            StaffSpell.ICICLE to false,
            StaffSpell.INFERNO to false,
            StaffSpell.INFERNO to true,
            StaffSpell.GRAVITY to false,
            StaffSpell.GRAVITY to true,
        )
        for (spell in StaffSpell.entries) for (secondary in listOf(false, true)) {
            val first = staffPreparationParts(spell, secondary, 0)
            if ((spell to secondary) in expectedEmpty) {
                first shouldBe emptyList()
                continue
            }
            first.isNotEmpty() shouldBe true
            (first.size <= 20) shouldBe true
            first.all { it.identity?.startsWith("preparation-") == true } shouldBe true
            first.map { it.identity }.distinct().size shouldBe first.size

            val identities = first.map { it.identity }
            for (age in listOf(1, 4, 8, 9)) {
                val parts = staffPreparationParts(spell, secondary, age)
                (parts.size <= 20) shouldBe true
                parts.map { it.identity } shouldBe identities
                parts.forEach { part ->
                    val values = listOf(part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z,
                        part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w)
                    withClue("$spell secondary=$secondary age=$age part=${part.identity} center=${part.center} scale=${part.scale}") {
                        (values.all(Float::isFinite) && part.scale.x > 0f && part.scale.y > 0f &&
                        part.scale.z > 0f && part.material.isBlock &&
                        part.center.y in 0.4f..1.5f && part.center.z in 3.1f..3.8f &&
                        part.center.length() - part.scale.length() * 0.5f >= 1.5f) shouldBe true
                    }
                }
            }
        }
    }

    "age four preparations appear in a normal camera view without covering the reticle" {
        val verticalSlope = tan(Math.toRadians(24.0)).toFloat()
        val horizontalSlope = verticalSlope * 1.53f
        for (spell in StaffSpell.entries) for (secondary in listOf(false, true)) {
            val parts = staffPreparationParts(spell, secondary, 4)
            if (parts.isEmpty()) continue

            withClue("$spell secondary=$secondary") {
                parts.any { part ->
                    val center = part.center
                    center.z > 0f && abs(center.y) <= center.z * verticalSlope &&
                        abs(center.x) <= center.z * horizontalSlope
                } shouldBe true
                parts.none(::preparationIntersectsReticle) shouldBe true
            }
        }
    }

    "instant spell families have authored silhouettes and secondary poses" {
        val families = listOf(StaffSpell.CHAIN, StaffSpell.MARK, StaffSpell.FROST, StaffSpell.FAULT,
            StaffSpell.LANCE, StaffSpell.NOVA, StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
            StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NECROMANCER, StaffSpell.OSSUARY)
        fun shape(spell: StaffSpell, secondary: Boolean) = staffPreparationParts(spell, secondary, 4)
            .map { part -> listOf(part.center.x, part.center.y, part.center.z,
                part.scale.x, part.scale.y, part.scale.z).joinToString(":") { (it * 100).roundToInt().toString() } }
            .sorted().joinToString("|")

        families.map { shape(it, false) }.distinct().size shouldBe families.size
        families.all { shape(it, false) != shape(it, true) } shouldBe true
    }

    "a preparation cue opens after its visible first frame and fades before removal" {
        val first = staffPreparationParts(StaffSpell.GALE, false, 0).first()
        val open = staffPreparationParts(StaffSpell.GALE, false, 3).first()
        val fading = staffPreparationParts(StaffSpell.GALE, false, 8).first()
        val last = staffPreparationParts(StaffSpell.GALE, false, 9).first()

        (first.scale.x > 0f) shouldBe true
        (first.scale.x < open.scale.x) shouldBe true
        (fading.scale.x > last.scale.x) shouldBe true
        staffPreparationParts(StaffSpell.GALE, false, 10) shouldBe emptyList()
    }

    "a preparation is nearly gone on the last frame of the two-tick preview cadence" {
        val atSix = staffPreparationParts(StaffSpell.GALE, false, 6).associateBy { it.identity }
        val atEight = staffPreparationParts(StaffSpell.GALE, false, 8).associateBy { it.identity }

        atEight.keys shouldBe atSix.keys
        atEight.forEach { (identity, part) ->
            val previous = atSix.getValue(identity)
            (part.scale.length() < previous.scale.length() * 0.16f) shouldBe true
        }
        staffPreparationParts(StaffSpell.GALE, false, 10) shouldBe emptyList()
    }
})

private fun preparationIntersectsReticle(part: StaffDisplayPart): Boolean {
    val inverse = Quaternionf(part.rotation).invert()
    val origin = inverse.transform(Vector3f(-part.center.x, -part.center.y, -part.center.z))
    val direction = inverse.transform(Vector3f(0f, 0f, 1f))
    var near = 0f
    var far = Float.POSITIVE_INFINITY
    for (axis in 0..2) {
        val rayOrigin = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
        val rayDirection = when (axis) { 0 -> direction.x; 1 -> direction.y; else -> direction.z }
        val extent = when (axis) { 0 -> part.scale.x; 1 -> part.scale.y; else -> part.scale.z } / 2f
        if (abs(rayDirection) < 1e-6f) {
            if (abs(rayOrigin) > extent) return false
        } else {
            val first = (-extent - rayOrigin) / rayDirection
            val second = (extent - rayOrigin) / rayDirection
            near = maxOf(near, minOf(first, second))
            far = minOf(far, maxOf(first, second))
            if (near > far) return false
        }
    }
    return far >= 0f
}
