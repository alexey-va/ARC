package ru.arc.investigation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.util.Common
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
        reloaded.records().single().case.narrative shouldBe prepared.case.narrative
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

    "legacy persisted cases remain valid when new evidence fields are absent" {
        val legacyJson =
            """
            {
              "transactionId": "00000000-0000-0000-0000-000000000001",
              "playerId": "00000000-0000-0000-0000-000000000002",
              "case": {
                "caseNumber": "А-0001",
                "seller": "Купец Лука",
                "goods": "рулоны сукна",
                "quantity": 10,
                "unitPrice": 5,
                "announcedTotal": 55,
                "archiveTotal": 50,
                "registeredSeal": "ключ над волной",
                "documentSeal": "ключ над волной",
                "registeredWax": "синий воск",
                "documentWax": "синий воск",
                "registeredInitials": "Л.К.",
                "documentInitials": "Л.К.",
                "oddity": "На полях лежит крошка сургуча.",
                "amountTrap": "ARITHMETIC",
                "sealTrap": "NONE",
                "verdict": "AMOUNT_MISMATCH",
                "stavrVariant": 0,
                "prokhorVariant": 1,
                "gordeyVariant": 2
              },
              "feeMinor": 10000,
              "rewardMinor": 30000,
              "status": "PREPARED",
              "createdAt": 1,
              "updatedAt": 1,
              "cluesMask": 0
            }
            """.trimIndent()

        val decoded = Common.prettyGson.fromJson(legacyJson, InvestigationJournalRecord::class.java).validated()

        decoded.case.declaredGoods shouldBe "рулоны сукна"
        decoded.case.inspectedGoods shouldBe "рулоны сукна"
        decoded.case.declaredQuantity shouldBe 10
        decoded.case.inspectedQuantity shouldBe 10
        decoded.case.entryReference shouldBe "А-0001"
        decoded.case.effectiveCargoTrap shouldBe CargoTrap.NONE
        decoded.case.effectiveLedgerTrap shouldBe LedgerTrap.NONE

        Common.prettyGson
            .fromJson(Common.prettyGson.toJson(decoded), InvestigationJournalRecord::class.java)
            .validated() shouldBe decoded
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
