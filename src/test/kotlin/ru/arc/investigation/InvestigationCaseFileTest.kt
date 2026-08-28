package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        visible shouldContain "Нужно установить"
        visible shouldContain record.case.question()
        visible shouldContain "Подозрительная зацепка"
        visible shouldContain "Как вести дело"
        visible shouldContain "После трёх показаний вернитесь к Фоме"
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

        lore.count { "<green>✔" in it } shouldBe 2
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
