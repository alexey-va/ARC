package ru.arc.investigation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import kotlin.random.Random

class InvestigationJournalTest : StringSpec({
    "journal survives reload and preserves generated evidence" {
        val root = Files.createTempDirectory("arc-investigation-journal-")
        val journal = FileInvestigationJournal(root)
        val prepared = preparedRecord()
        journal.persist(prepared) shouldBe true
        journal.persist(prepared.withdrawalStarted()) shouldBe true
        journal.persist(prepared.active()) shouldBe true
        val withClue = prepared.active().copy(updatedAt = 5L, cluesMask = InvestigationWitness.STAVR.bit)
        journal.persist(withClue) shouldBe true

        val reloaded = FileInvestigationJournal(root)

        reloaded.records().single() shouldBe withClue
        reloaded.open(UUID.fromString(prepared.playerId)) shouldBe withClue
    }

    "journal rejects skipped money transitions and forgotten clues" {
        val journal = FileInvestigationJournal(Files.createTempDirectory("arc-investigation-transitions-"))
        val prepared = preparedRecord()
        journal.persist(prepared) shouldBe true
        shouldThrow<IllegalArgumentException> {
            journal.persist(prepared.active())
        }
        journal.persist(prepared.withdrawalStarted()) shouldBe true
        journal.persist(prepared.active()) shouldBe true
        journal.persist(prepared.active().copy(updatedAt = 5L, cluesMask = 1)) shouldBe true
        shouldThrow<IllegalArgumentException> {
            journal.persist(prepared.active().copy(updatedAt = 6L, cluesMask = 0))
        }
    }
})

private fun preparedRecord() =
    InvestigationJournalRecord(
        transactionId = UUID.randomUUID().toString(),
        playerId = UUID.randomUUID().toString(),
        case = InvestigationCaseGenerator.generate(Random(1)),
        feeMinor = 10_000L,
        rewardMinor = 30_000L,
        createdAt = 1L,
    )

private fun InvestigationJournalRecord.withdrawalStarted() =
    copy(
        status = InvestigationStatus.WITHDRAWAL_STARTED,
        updatedAt = 2L,
        withdrawalStartedAt = 2L,
        feeBalanceBeforeMinor = 100_000L,
    )

private fun InvestigationJournalRecord.active() =
    withdrawalStarted().copy(
        status = InvestigationStatus.ACTIVE,
        updatedAt = 3L,
        activeAt = 3L,
        expiresAt = 90_003L,
        cooldownUntil = 72_000_003L,
        feeBalanceAfterMinor = 90_000L,
        evidence = "exact_fee_balance_delta",
    )
