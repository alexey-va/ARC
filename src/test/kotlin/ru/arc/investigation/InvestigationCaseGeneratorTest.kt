package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    "clean cases keep all decisive facts aligned despite their suspicious decoy" {
        val random = Random(9)
        val clean = generateSequence { InvestigationCaseGenerator.generate(random) }.first { it.verdict == InvestigationVerdict.CLEAN }

        clean.announcedTotal shouldBe clean.expectedTotal
        clean.archiveTotal shouldBe clean.expectedTotal
        clean.registeredSeal shouldBe clean.documentSeal
        clean.registeredWax shouldBe clean.documentWax
        clean.registeredInitials shouldBe clean.documentInitials
    }
})
