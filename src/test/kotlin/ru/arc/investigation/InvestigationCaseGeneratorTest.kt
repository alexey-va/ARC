package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.random.Random

class InvestigationCaseGeneratorTest : StringSpec({
    "generated cases always contain exactly one fair verdict" {
        val random = Random(1942)
        val cases = List(1_000) { InvestigationCaseGenerator.generate(random) }

        cases.forEach(InvestigationCase::validated)
        cases.map(InvestigationCase::verdict).toSet() shouldContainAll InvestigationVerdict.entries
        cases.map(InvestigationCase::fingerprint).distinct().size shouldNotBe 1
    }

    "a new paid run does not repeat the previous evidence set" {
        val random = Random(77)
        val first = InvestigationCaseGenerator.generate(random)
        val second = InvestigationCaseGenerator.generate(random, first)

        second.fingerprint() shouldNotBe first.fingerprint()
    }

    "catalog exposes every authored plot and moves its answer between all five slots" {
        val random = Random(9)
        val cases = List(8_000) { InvestigationCaseGenerator.generate(random) }

        cases.mapNotNull { it.narrative?.plotId }.toSet() shouldBe InvestigationStoryCatalog.plotIds
        cases.map(InvestigationCase::verdict).toSet() shouldBe InvestigationVerdict.entries.toSet()
        cases.map(InvestigationCase::fingerprint).distinct().size shouldBeGreaterThan 7_500
    }

    "every generated investigation is a complete playable reconstruction" {
        val random = Random(813)

        repeat(2_000) {
            val case = InvestigationCaseGenerator.generate(random)
            val narrative = requireNotNull(case.narrative)

            narrative.timeline.size shouldBe 5
            narrative.timeline.map(InvestigationTimelineBeat::witness).toSet() shouldBe
                InvestigationWitness.entries.map(InvestigationWitness::commandValue).toSet()
            narrative.testimonies.keys shouldBe InvestigationWitness.entries.map(InvestigationWitness::commandValue).toSet()
            narrative.crossChecks.size shouldBe 5
            narrative.conclusions.keys shouldBe InvestigationVerdict.entries.map(InvestigationVerdict::commandValue).toSet()
            narrative.conclusions.values.map(InvestigationConclusion::title).distinct().size shouldBe 5

            InvestigationWitness.entries.forEach { witness ->
                case.testimony(witness).all(String::isNotBlank) shouldBe true
            }
            everyThreeWitnessRouteUnlocksAComparison(narrative) shouldBe true
        }
    }

    "dynamic dossier lore is wrapped without dropping its continuation" {
        val source = listOf("<gray>Перед полуночью неизвестный вернул телегу к боковым воротам за якобы забытой биркой.")

        val wrapped = wrapInvestigationLore(source, 32)

        wrapped.size shouldBeGreaterThan 1
        wrapped.drop(1).forEach { it shouldStartWith "<dark_gray>  " }
        wrapped.forEach { line ->
            line.replace(Regex("<[^>]+>"), "").length shouldBeLessThanOrEqual 32
        }
    }
})

private fun everyThreeWitnessRouteUnlocksAComparison(narrative: InvestigationNarrative): Boolean {
    val witnesses = InvestigationWitness.entries
    for (first in witnesses.indices) {
        for (second in first + 1 until witnesses.size) {
            for (third in second + 1 until witnesses.size) {
                val mask = witnesses[first].bit or witnesses[second].bit or witnesses[third].bit
                if (narrative.crossChecks.none { it.unlocked(mask) }) return false
            }
        }
    }
    return true
}
