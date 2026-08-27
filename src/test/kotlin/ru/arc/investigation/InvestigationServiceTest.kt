package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

class InvestigationServiceTest : StringSpec({
    "correct verdict after two witnesses charges once and rewards once" {
        val fixture = InvestigationFixture()
        val started = fixture.service.start(fixture.playerId) as InvestigationStartResult.Started
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.STAVR) as InvestigationClueResult.Evidence
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.PROKHOR) as InvestigationClueResult.Evidence

        val result = fixture.service.submitVerdict(fixture.playerId, started.record.case.verdict)
        val duplicate = fixture.service.submitVerdict(fixture.playerId, started.record.case.verdict)

        result::class shouldBe InvestigationVerdictResult.Success::class
        duplicate shouldBe InvestigationVerdictResult.NoActiveCase
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.deposits shouldBe 1
        fixture.wallet.balance shouldBe 120_000L
        fixture.journal.latest(fixture.playerId)?.status shouldBe InvestigationStatus.COMPLETED
    }

    "one witness is not enough to gamble" {
        val fixture = InvestigationFixture()
        val started = fixture.service.start(fixture.playerId) as InvestigationStartResult.Started
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.STAVR)

        fixture.service.submitVerdict(fixture.playerId, started.record.case.verdict) shouldBe InvestigationVerdictResult.NeedClues(1)
        fixture.wallet.deposits shouldBe 0
        fixture.journal.open(fixture.playerId)?.status shouldBe InvestigationStatus.ACTIVE
    }

    "wrong verdict closes the paid case without a reward and keeps the cooldown" {
        val fixture = InvestigationFixture()
        val started = fixture.service.start(fixture.playerId) as InvestigationStartResult.Started
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.STAVR)
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.GORDEY)
        val wrong = InvestigationVerdict.entries.first { it != started.record.case.verdict }

        fixture.service.submitVerdict(fixture.playerId, wrong)::class shouldBe InvestigationVerdictResult.Wrong::class
        val restart = fixture.service.start(fixture.playerId)

        restart::class shouldBe InvestigationStartResult.Cooldown::class
        fixture.wallet.withdrawals shouldBe 1
        fixture.wallet.deposits shouldBe 0
        fixture.wallet.balance shouldBe 90_000L
    }

    "timeout is durable and cannot be answered after the deadline" {
        val fixture = InvestigationFixture()
        fixture.service.start(fixture.playerId) as InvestigationStartResult.Started
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.STAVR)
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.PROKHOR)
        fixture.now += 90_010L

        fixture.service.submitVerdict(fixture.playerId, InvestigationVerdict.CLEAN)::class shouldBe InvestigationVerdictResult.Expired::class
        fixture.journal.latest(fixture.playerId)?.status shouldBe InvestigationStatus.FAILED
        fixture.wallet.deposits shouldBe 0
    }

    "ambiguous withdrawal locks replay and recovery uses exact provider history" {
        val fixture = InvestigationFixture().also { it.wallet.ambiguousWithdrawal = true }

        fixture.service.start(fixture.playerId) shouldBe InvestigationStartResult.ManualReview
        fixture.service.start(fixture.playerId) shouldBe InvestigationStartResult.ManualReview
        fixture.wallet.withdrawals shouldBe 1

        fixture.wallet.ambiguousWithdrawal = false
        val manual = mutableListOf<InvestigationJournalRecord>()
        fixture.service.recover(manual::add)

        fixture.journal.open(fixture.playerId)?.status shouldBe InvestigationStatus.ACTIVE
        manual shouldBe emptyList()
        fixture.wallet.withdrawals shouldBe 1
    }

    "interrupted reward is completed from history without a second deposit" {
        val fixture = InvestigationFixture().also { it.wallet.ambiguousDeposit = true }
        val started = fixture.service.start(fixture.playerId) as InvestigationStartResult.Started
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.STAVR)
        fixture.service.collectClue(fixture.playerId, InvestigationWitness.PROKHOR)

        fixture.service.submitVerdict(fixture.playerId, started.record.case.verdict) shouldBe InvestigationVerdictResult.ManualReview
        fixture.wallet.deposits shouldBe 1
        val manual = mutableListOf<InvestigationJournalRecord>()
        fixture.service.recover(manual::add)

        fixture.journal.latest(fixture.playerId)?.status shouldBe InvestigationStatus.COMPLETED
        fixture.wallet.deposits shouldBe 1
        manual shouldBe emptyList()
    }

    "insufficient funds never starts a cooldown or another mutation" {
        val fixture = InvestigationFixture().also { it.wallet.balance = 9_999L }

        fixture.service.start(fixture.playerId) shouldBe InvestigationStartResult.InsufficientFunds
        fixture.service.start(fixture.playerId) shouldBe InvestigationStartResult.InsufficientFunds

        fixture.wallet.withdrawals shouldBe 0
        fixture.journal.open(fixture.playerId) shouldBe null
        fixture.journal.latest(fixture.playerId)?.cooldownUntil shouldBe null
    }
})

private class InvestigationFixture {
    val playerId: UUID = UUID.randomUUID()
    var now: Long = 1_000L
    val wallet = MutableInvestigationWallet()
    val journal = FileInvestigationJournal(Files.createTempDirectory("arc-investigation-service-"))
    val service =
        InvestigationService(
            journal = journal,
            wallet = wallet,
            enabled = { true },
            feeMinor = { 10_000L },
            rewardMinor = { 30_000L },
            duration = { Duration.ofSeconds(90) },
            cooldown = { Duration.ofHours(20) },
            runSync = { it() },
            clock = { now },
            random = Random(42),
        )
}

private class MutableInvestigationWallet : InvestigationWallet {
    override val available: Boolean = true
    var balance: Long = 100_000L
    var withdrawals = 0
    var deposits = 0
    var ambiguousWithdrawal = false
    var ambiguousDeposit = false
    private val history = mutableListOf<History>()

    override fun balanceMinor(playerId: UUID): Long = balance

    override fun withdraw(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): InvestigationMoneyEvidence {
        withdrawals++
        balance -= amountMinor
        history += History(-amountMinor, reason, 1_000L)
        return if (ambiguousWithdrawal) {
            InvestigationMoneyEvidence(null, true, null, "provider_threw")
        } else {
            InvestigationMoneyEvidence(true, true, balance)
        }
    }

    override fun deposit(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): InvestigationMoneyEvidence {
        deposits++
        balance += amountMinor
        history += History(amountMinor, reason, 1_000L)
        return if (ambiguousDeposit) {
            InvestigationMoneyEvidence(null, true, null, "provider_threw")
        } else {
            InvestigationMoneyEvidence(true, true, balance)
        }
    }

    override fun findTransaction(
        playerId: UUID,
        amountMinor: Long,
        reason: String,
        notBeforeMillis: Long,
    ): CompletableFuture<InvestigationProviderEvidence> {
        val found = history.firstOrNull { it.amount == amountMinor && it.reason == reason && it.at >= notBeforeMillis }
        return CompletableFuture.completedFuture(InvestigationProviderEvidence(found?.let { "provider-1" }, true))
    }

    private data class History(val amount: Long, val reason: String, val at: Long)
}
