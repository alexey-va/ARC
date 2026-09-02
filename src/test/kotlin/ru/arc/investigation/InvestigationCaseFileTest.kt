package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.random.Random

class InvestigationCaseFileTest : StringSpec({
    "case file explains the complete setup and every witness route" {
        val record = activeCaseRecord()
        val lore = InvestigationCaseFile.caseFileLore(record)
        val visible =
            lore.joinToString(" ") { it.replace(Regex("<[^>]+>"), "") }
                .replace(Regex("\\s+"), " ")

        visible shouldContain "Что произошло"
        visible shouldContain "Кто обратился"
        visible shouldContain requireNotNull(record.case.narrative?.requester)
        visible shouldContain "Почему это важно"
        requireNotNull(record.case.narrative?.stakes).forEach { visible shouldContain it }
        visible shouldContain "Главный вопрос"
        visible shouldContain record.case.question()
        visible shouldContain "Что делать"
        visible shouldContain "Соберите показания и вернитесь к Фоме"
        visible shouldContain "Ошибка сразу закрывает дело"
        visible shouldContain "ПКМ предметом — открыть материалы"
        record.case.witnesses().forEach { witness ->
            visible shouldContain witness.displayName
            visible shouldContain witness.locationHint
        }
    }

    "case file marks collected witnesses without changing the roster" {
        val base = activeCaseRecord()
        val firstTwoMask = base.case.witnesses().take(2).fold(0) { mask, witness -> mask or witness.bit }
        val lore = InvestigationCaseFile.caseFileLore(base.copy(cluesMask = firstTwoMask))

        lore.count { "<white>✔</white>" in it } shouldBe 2
        lore.joinToString("\n").let { text ->
            base.case.witnesses().map(InvestigationWitness::displayName).forEach { text shouldContain it }
        }
    }

    "simplified investigation roles keep one task card and one focused verdict flow" {
        InvestigationGuiRole.entries.map(InvestigationGuiRole::configKey) shouldContainAll
            listOf("next-step", "evidence", "choose-verdict", "return-to-foma", "back", "case-file")
        InvestigationGuiRole.entries.map(InvestigationGuiRole::configKey).toSet().intersect(
            setOf("status", "timeline", "cross-check", "rules"),
        ).isEmpty() shouldBe true
    }

    "collected testimony is complete in the main materials card" {
        val base = activeCaseRecord()
        val witness = base.case.witnesses().first()
        val record = base.copy(cluesMask = witness.bit)
        val visible =
            witnessLore(record, witness)
                .joinToString(" ") { it.replace(Regex("<[^>]+>"), "") }
                .replace(Regex("\\s+"), " ")

        base.case.testimony(witness).forEach { line ->
            visible shouldContain line.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ")
        }
        visible shouldContain "Слова свидетеля"
        visible shouldContain "Что это устанавливает"
        visible shouldNotContain "Нажмите, чтобы перечитать"
    }

    "witness click focus contains only that witness statement" {
        val record = activeCaseRecord()
        val witness = record.case.witnesses().first()
        val visible =
            focusedTestimonyLore(record, witness)
                .joinToString(" ") { it.replace(Regex("<[^>]+>"), "") }
                .replace(Regex("\\s+"), " ")

        record.case.testimony(witness).forEach { line ->
            visible shouldContain line.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ")
        }
        visible shouldNotContain "Что это устанавливает"
        visible shouldNotContain "Связи с другими показаниями"
        visible shouldNotContain witness.displayName
    }

    "all custom money glyphs declare an explicit white color" {
        val lore = InvestigationCaseFile.caseFileLore(activeCaseRecord()).joinToString("\n")
        lore shouldContain "<white>💰</white>"
        lore.replace("<white>💰</white>", "") shouldNotContain "💰"
    }

    "legacy, foreign and expired books are removed while an active owned book survives" {
        val playerId = UUID.randomUUID()
        shouldRemoveCaseFile(playerId, null, playerId, 100L) shouldBe true
        shouldRemoveCaseFile(UUID.randomUUID(), 200L, playerId, 100L) shouldBe true
        shouldRemoveCaseFile(playerId, 100L, playerId, 100L) shouldBe true
        shouldRemoveCaseFile(playerId, 101L, playerId, 100L) shouldBe false
    }

    "glow follows uncollected witnesses and points back to Foma after three statements" {
        val base = activeCaseRecord()
        desiredInvestigationTargetRoles(base, inSceneWorld = true) shouldBe
            base.case.witnesses().map(InvestigationWitness::commandValue).toSet()

        val firstThree = base.case.witnesses().take(3).fold(0) { mask, witness -> mask or witness.bit }
        desiredInvestigationTargetRoles(base.copy(cluesMask = firstThree), inSceneWorld = true) shouldBe
            (base.case.witnesses().drop(3).map(InvestigationWitness::commandValue) + "foma").toSet()
        desiredInvestigationTargetRoles(base, inSceneWorld = false) shouldBe emptySet()
    }
})

private fun activeCaseRecord(): InvestigationJournalRecord {
    val playerId = UUID.randomUUID()
    return InvestigationJournalRecord(
        transactionId = UUID.randomUUID().toString(),
        playerId = playerId.toString(),
        case = investigationCaseGeneratorForTest().generate(Random(44)),
        feeMinor = 10_000L,
        rewardMinor = 30_000L,
        status = InvestigationStatus.ACTIVE,
        createdAt = 1L,
        updatedAt = 3L,
        withdrawalStartedAt = 2L,
        activeAt = 3L,
        expiresAt = 120_003L,
        cooldownUntil = 72_000_003L,
        feeBalanceBeforeMinor = 100_000L,
        feeBalanceAfterMinor = 90_000L,
        evidence = "exact_fee_balance_delta",
    ).validated()
}
