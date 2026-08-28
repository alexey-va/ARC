package ru.arc.investigation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.random.Random

class InvestigationCaseGeneratorTest : StringSpec({
    "generated cases always contain exactly one fair verdict" {
        val random = Random(1942)
        val generator = investigationCaseGeneratorForTest()
        val cases = List(1_000) { generator.generate(random) }

        cases.forEach(InvestigationCase::validated)
        cases.map(InvestigationCase::verdict).toSet() shouldContainAll InvestigationVerdict.entries
        cases.map(InvestigationCase::fingerprint).distinct().size shouldNotBe 1
    }

    "a new paid run does not repeat the previous authored plot or evidence set" {
        val random = Random(77)
        val generator = investigationCaseGeneratorForTest()
        val first = generator.generate(random)
        val second = generator.generate(random, first)

        second.fingerprint() shouldNotBe first.fingerprint()
        second.narrative?.plotId shouldNotBe first.narrative?.plotId
    }

    "catalog exposes every authored plot and moves its answer between all five slots" {
        val random = Random(9)
        val generator = investigationCaseGeneratorForTest()
        val cases = List(8_000) { generator.generate(random) }

        bundledInvestigationCatalogForTest.storyCount shouldBe 25
        bundledInvestigationCatalogForTest.witnessKeys.size shouldBe 13
        cases.mapNotNull { it.narrative?.plotId }.toSet() shouldBe bundledInvestigationCatalogForTest.plotIds
        cases.map(InvestigationCase::verdict).toSet() shouldBe InvestigationVerdict.entries.toSet()
        cases.map(InvestigationCase::fingerprint).distinct().size shouldBeGreaterThan 7_000
    }

    "all configured witnesses participate in at least one authored plot" {
        val used =
            bundledInvestigationCatalogForTest.plotIds.flatMapIndexed { index, plotId ->
                bundledInvestigationCatalogForTest
                    .generatePlot(plotId, Random(index + 91), "А-${1000 + index}")
                    .narrative
                    .witnessRoster()
                    .map(InvestigationWitness::commandValue)
            }.toSet()

        used shouldBe bundledInvestigationCatalogForTest.witnessKeys
    }

    "one malformed configured case is skipped without losing the valid catalog" {
        val root = Files.createTempDirectory("arc-investigation-malformed-")
        val config = Config(root, "modules/investigation-cases.yml")
        config.setString("cases.missing_pie.question", "")

        val catalog = InvestigationStoryCatalog.parse(config)

        catalog.storyCount shouldBe 24
        ("missing_pie" in catalog.plotIds) shouldBe false
        ("ring_in_ashes" in catalog.plotIds) shouldBe true
    }

    "catalog rejects an invalid witness material before a case can be sold" {
        val root = Files.createTempDirectory("arc-investigation-material-")
        val config = Config(root, "modules/investigation-cases.yml")
        config.setString("witnesses.stavr.material", "NOT_A_REAL_ITEM")

        shouldThrow<IllegalArgumentException> {
            InvestigationStoryCatalog.parse(config)
        }
    }

    "catalog rejects nested placeholders in variable pools" {
        val root = Files.createTempDirectory("arc-investigation-variable-")
        val config = Config(root, "modules/investigation-cases.yml")
        config.setStringList("variables.seller", listOf("купец {goods}"))

        shouldThrow<IllegalArgumentException> {
            InvestigationStoryCatalog.parse(config)
        }
    }

    "every generated investigation is a complete playable reconstruction" {
        val random = Random(813)
        val generator = investigationCaseGeneratorForTest()

        repeat(2_000) {
            val case = generator.generate(random)
            val narrative = requireNotNull(case.narrative)
            val witnesses = narrative.witnessRoster()
            val witnessKeys = witnesses.map(InvestigationWitness::commandValue).toSet()

            narrative.timeline.size shouldBe 5
            narrative.timeline.map(InvestigationTimelineBeat::witness).toSet() shouldBe witnessKeys
            narrative.testimonies.keys shouldBe witnessKeys
            narrative.crossChecks.size shouldBe 5
            narrative.conclusions.keys shouldBe InvestigationVerdict.entries.map(InvestigationVerdict::commandValue).toSet()
            narrative.conclusions.values.map(InvestigationConclusion::title).distinct().size shouldBe 5

            witnesses.forEach { witness ->
                case.testimony(witness).all(String::isNotBlank) shouldBe true
            }
            everyThreeWitnessRouteUnlocksAComparison(narrative) shouldBe true
        }
    }

    "dynamic dossier lore is wrapped without dropping its continuation" {
        val source = listOf("<gray>Перед полуночью кто-то оставил длинную записку у боковых ворот и убежал под дождём.")

        val wrapped = wrapInvestigationLore(source, 32)

        wrapped.size shouldBeGreaterThan 1
        wrapped.drop(1).forEach { it shouldStartWith "<gray>  " }
        wrapped.joinToString("\n") shouldNotContain "<dark_gray>  "
        wrapped.forEach { line ->
            line.replace(Regex("<[^>]+>"), "").length shouldBeLessThanOrEqual 32
        }
    }

    "testimony contains only the witness words without repeated attribution" {
        val case = investigationCaseGeneratorForTest().generate(Random(511))
        val witness = case.witnesses().first()
        val testimony = case.testimony(witness).joinToString(" ")

        testimony shouldNotContain witness.displayName
        testimony shouldNotContain "»"
        testimony shouldNotContain "<dark_gray>"
    }
})

private fun everyThreeWitnessRouteUnlocksAComparison(narrative: InvestigationNarrative): Boolean {
    val witnesses = narrative.witnessRoster()
    val witnessMap = witnesses.associateBy(InvestigationWitness::commandValue)
    for (first in witnesses.indices) {
        for (second in first + 1 until witnesses.size) {
            for (third in second + 1 until witnesses.size) {
                val mask = witnesses[first].bit or witnesses[second].bit or witnesses[third].bit
                if (narrative.crossChecks.none { it.unlocked(mask, witnessMap) }) return false
            }
        }
    }
    return true
}
