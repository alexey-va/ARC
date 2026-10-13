package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt

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
                        part.center.y in 1.3f..2.4f && part.center.z in 1.8f..2.5f &&
                        part.center.length() - part.scale.length() * 0.5f >= 1.5f) shouldBe true
                    }
                }
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
})
