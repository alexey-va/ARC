package ru.arc.onboarding

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.economy.CurrencyMoneyEvidence
import ru.arc.economy.CurrencyTransactionEvidence
import ru.arc.economy.CurrencyWallet
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class OnboardingRewardsTest : FunSpec({
    test("all four actions pay each currency once across runs and controller instances") {
        RewardFixture().use { fixture ->
            val first = fixture.controller()
            val progress = earnedProgress(allStages = true)
            fixture.await(fixture.reconcile(first, progress))
            fixture.wallet.balance("vault") shouldBe 80_000L
            fixture.wallet.balance("tokens") shouldBe 400L
            fixture.wallet.calls.size shouldBe 8
            fixture.wallet.calls.map { it.reason }.distinct().size shouldBe 8
            first.ledger.claimCalls shouldBe 8

            fixture.await(fixture.reconcile(first, earnedProgress(allStages = true)))
            first.ledger.claimCalls shouldBe 8 // Paid identities do not poll SQL again.
            val second = fixture.controller()
            fixture.await(fixture.reconcile(second, earnedProgress(allStages = true)))
            second.ledger.claimCalls shouldBe 8
            fixture.wallet.calls.size shouldBe 8
            fixture.backend.rows.values.all { it.committed } shouldBe true
        }
    }

    test("concurrent controllers and duplicate reconciliation cannot both obtain execution authority") {
        RewardFixture().use { fixture ->
            val first = fixture.controller().also { it.ledger.delayClaims = true }
            val second = fixture.controller()
            val pending = fixture.reconcile(first)
            val duplicate = fixture.reconcile(first)
            fixture.await(fixture.reconcile(second))
            first.ledger.claimCalls shouldBe 2
            second.ledger.claimCalls shouldBe 2
            fixture.wallet.calls shouldBe emptyList()
            pending.isDone shouldBe false
            duplicate.isDone shouldBe false

            first.ledger.completeClaims()
            fixture.await(pending)
            fixture.await(duplicate)
            fixture.await(fixture.reconcile(second))
            fixture.wallet.calls.size shouldBe 2
            fixture.wallet.balance("vault") shouldBe 20_000L
            fixture.wallet.balance("tokens") shouldBe 100L
        }
    }

    test("proven provider rejection releases only that currency and permits a fresh safe retry") {
        RewardFixture().use { fixture ->
            fixture.wallet.modes["vault"] = RewardDepositMode.REJECT
            val controller = fixture.controller()
            fixture.await(fixture.reconcile(controller))
            fixture.wallet.balance("vault") shouldBe 0L
            fixture.wallet.balance("tokens") shouldBe 100L
            controller.ledger.releaseCalls shouldBe 1
            fixture.backend.rows.size shouldBe 1

            fixture.wallet.modes["vault"] = RewardDepositMode.SUCCESS
            fixture.await(fixture.reconcile(controller))
            fixture.wallet.balance("vault") shouldBe 20_000L
            fixture.wallet.balance("tokens") shouldBe 100L
            fixture.wallet.calls.size shouldBe 3
            controller.ledger.claimCalls shouldBe 3
            fixture.backend.rows.size shouldBe 2
        }
    }

    test("ambiguous or partially applied deposits and failed commits never repay; exact history can finish recovery") {
        for (mode in listOf(RewardDepositMode.THROW_AFTER_EFFECT, RewardDepositMode.PARTIAL, RewardDepositMode.SUCCESS)) {
            RewardFixture().use { fixture ->
                fixture.wallet.modes["vault"] = mode
                fixture.wallet.historyAvailable = false
                val first = fixture.controller()
                if (mode == RewardDepositMode.SUCCESS) first.ledger.failNextCommits = 1
                fixture.await(fixture.reconcile(first))
                first.ledger.abandonCalls shouldBe 1
                fixture.wallet.calls.size shouldBe 2
                fixture.await(fixture.reconcile(first))
                first.ledger.claimCalls shouldBe 2 // Held identities cannot retry on the current runtime.
                fixture.close(first)

                val recovering = fixture.controller()
                fixture.await(fixture.reconcile(recovering))
                fixture.wallet.calls.size shouldBe 2
                recovering.ledger.abandonCalls shouldBe 1
                fixture.close(recovering)

                fixture.wallet.historyAvailable = true
                val withEvidence = fixture.controller()
                fixture.await(fixture.reconcile(withEvidence))
                fixture.wallet.calls.size shouldBe 2
                fixture.wallet.balance("vault") shouldBe if (mode == RewardDepositMode.PARTIAL) 10_000L else 20_000L
                fixture.wallet.balance("tokens") shouldBe 100L
                withEvidence.ledger.commitCalls shouldBe if (mode == RewardDepositMode.PARTIAL) 0 else 1
                fixture.wallet.historyQueries.all { it.notBeforeMillis == 0L && it.amount > 0L } shouldBe true
            }
        }
    }

    test("a changed amount conflicts with the lifetime fingerprint instead of creating another payout") {
        RewardFixture().use { fixture ->
            val first = fixture.controller()
            fixture.await(fixture.reconcile(first))
            fixture.await(first.rewards.reconcile(fixture.player, earnedProgress(), 25_000L, 100L))
            first.ledger.claimCalls shouldBe 3
            fixture.diagnostics.count { it == "identity_conflict" } shouldBe 1
            val other = fixture.controller()
            fixture.await(other.rewards.reconcile(fixture.player, earnedProgress(), 25_000L, 100L))
            fixture.wallet.calls.size shouldBe 2
            fixture.wallet.balance("vault") shouldBe 20_000L
            fixture.wallet.balance("tokens") shouldBe 100L
        }
    }

    test("shutdown while a claim waits drains its release and prevents every late provider call") {
        RewardFixture().use { fixture ->
            val controller = fixture.controller().also { it.ledger.delayClaims = true }
            val pending = fixture.reconcile(controller)
            val drain = controller.rewards.closeAsync()
            controller.scope.close()
            drain.isDone shouldBe false
            controller.ledger.completeClaims()
            fixture.await(pending)
            fixture.await(drain)
            fixture.wallet.calls shouldBe emptyList()
            controller.ledger.releaseCalls shouldBe 2
            fixture.backend.rows shouldBe emptyMap()
            fixture.await(fixture.reconcile(controller))
            controller.ledger.claimCalls shouldBe 2
        }
    }
})

private fun earnedProgress(allStages: Boolean = false): OnboardingProgress {
    var progress = OnboardingProgress.offered().with(status = OnboardingStatus.ACTIVE)
    val place = OnboardingPlace.fromBlock("vanilla", 10, 10)
    progress = OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, place)
    if (!allStages) return progress
    progress = OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, place)
    progress = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, place)
    val operationId = UUID.randomUUID().toString()
    progress = OnboardingJourney.observe(progress, OnboardingMilestone.AUTOBUILD_STARTED, place, operationId = operationId)
    return OnboardingJourney.observe(progress, OnboardingMilestone.AUTOBUILD_COMPLETE, place, operationId = operationId)
}

private class RewardFixture : AutoCloseable {
    private val playerId = UUID.randomUUID()
    val player = mockk<Player>().also {
        every { it.uniqueId } returns playerId
        every { it.isOnline } returns true
    }
    private var native = false
    private val scheduler = TestTaskScheduler(Executor { action ->
        native = true
        try { action.run() } finally { native = false }
    })
    val wallet = RewardWallet(playerId) { check(native) { "Wallet access escaped the native executor" } }
    val backend = RewardLedgerBackend()
    val diagnostics = mutableListOf<String>()
    private val controllers = mutableListOf<RewardControllerFixture>()

    fun controller(): RewardControllerFixture {
        val ledger = RewardLedger(backend)
        val scope = LifecycleTaskScope(scheduler)
        return RewardControllerFixture(OnboardingRewards(ledger, wallet, scope, { true }, { _, _, reason, _ ->
            diagnostics += reason
        }), ledger, scope).also(controllers::add)
    }

    fun reconcile(controller: RewardControllerFixture, progress: OnboardingProgress = earnedProgress()) =
        controller.rewards.reconcile(player, progress, 20_000L, 100L)

    fun await(future: CompletableFuture<*>) {
        repeat(20) { if (!future.isDone) scheduler.executeImmediate() }
        check(future.isDone) { "Reward operation did not settle" }
        future.join()
    }

    fun close(controller: RewardControllerFixture) {
        val drain = controller.rewards.closeAsync()
        controller.scope.close()
        controller.ledger.completeClaims()
        await(drain)
        controller.ledger.close()
    }

    override fun close() { controllers.forEach(::close) }
}

private data class RewardControllerFixture(val rewards: OnboardingRewards, val ledger: RewardLedger, val scope: LifecycleTaskScope)

private class RewardLedgerBackend {
    data class Row(val request: OneTimeUseClaimRequest, var committed: Boolean = false)
    val rows = mutableMapOf<UUID, Row>()
    val locks = mutableMapOf<UUID, RewardLedger>()
}

/** A protocol fake shared by independent controllers; release deletes the durable row as MySQL does. */
private class RewardLedger(private val backend: RewardLedgerBackend) : OneTimeUseLedger {
    var claimCalls = 0
    var commitCalls = 0
    var releaseCalls = 0
    var abandonCalls = 0
    var failNextCommits = 0
    var delayClaims = false
    private val delayed = mutableListOf<Pair<CompletableFuture<OneTimeUseClaimResult>, OneTimeUseClaimResult>>()

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        claimCalls++
        val id = request.identity.useId
        val row = backend.rows[id]
        val result = when {
            row != null && row.request.identity.fingerprint != request.identity.fingerprint -> OneTimeUseClaimResult.IdentityConflict
            row?.committed == true -> OneTimeUseClaimResult.AlreadyConsumed
            backend.locks.containsKey(id) || row != null && row.request != request -> OneTimeUseClaimResult.Busy
            else -> {
                backend.rows.putIfAbsent(id, RewardLedgerBackend.Row(request))
                backend.locks[id] = this
                OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = row == null))
            }
        }
        return if (delayClaims) CompletableFuture<OneTimeUseClaimResult>().also { delayed += it to result }
        else CompletableFuture.completedFuture(result)
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        commitCalls++
        if (failNextCommits > 0) {
            failNextCommits--
            return CompletableFuture.failedFuture(IllegalStateException("uncertain commit"))
        }
        val row = backend.rows[claim.identity.useId]
        val result = when {
            row == null || row.request != claim.asRequest() -> OneTimeUseCommitResult.REJECTED
            row.committed -> OneTimeUseCommitResult.ALREADY_COMMITTED
            else -> { row.committed = true; OneTimeUseCommitResult.COMMITTED }
        }
        backend.locks.remove(claim.identity.useId, this)
        return CompletableFuture.completedFuture(result)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> {
        releaseCalls++
        val row = backend.rows[claim.identity.useId]
        val result = when {
            row == null -> OneTimeUseReleaseResult.ALREADY_RELEASED
            row.request != claim.asRequest() || row.committed -> OneTimeUseReleaseResult.REJECTED
            else -> { backend.rows.remove(claim.identity.useId); OneTimeUseReleaseResult.RELEASED }
        }
        backend.locks.remove(claim.identity.useId, this)
        return CompletableFuture.completedFuture(result)
    }

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> {
        abandonCalls++
        val row = backend.rows[claim.identity.useId]
        backend.locks.remove(claim.identity.useId, this)
        return CompletableFuture.completedFuture(when {
            row == null || row.request != claim.asRequest() -> OneTimeUseAbandonResult.REJECTED
            row.committed -> OneTimeUseAbandonResult.ALREADY_COMMITTED
            else -> OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY
        })
    }

    fun completeClaims() {
        delayed.toList().also { delayed.clear() }.forEach { (future, result) -> future.complete(result) }
    }

    override fun close() { backend.locks.entries.removeIf { it.value === this } }
}

private enum class RewardDepositMode { SUCCESS, REJECT, PARTIAL, THROW_AFTER_EFFECT }

private class RewardWallet(private val playerId: UUID, private val requireNative: () -> Unit) : CurrencyWallet {
    data class Call(val currency: String, val amount: Long, val reason: String, val notBeforeMillis: Long = 0L)
    val calls = mutableListOf<Call>()
    val historyQueries = mutableListOf<Call>()
    val modes = mutableMapOf<String, RewardDepositMode>()
    var historyAvailable = true
    private val balances = mutableMapOf<String, Long>()
    private val history = mutableMapOf<String, Call>()
    override val available = true
    fun balance(currency: String): Long = balances[currency] ?: 0L

    override fun walletForCurrency(currency: String): CurrencyWallet = object : CurrencyWallet {
        override val available: Boolean get() { requireNative(); return true }
        override fun balanceMinor(playerId: UUID): Long { requireNative(); return balance(currency) }
        override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): CurrencyMoneyEvidence {
            requireNative()
            playerId shouldBe this@RewardWallet.playerId
            balance(currency) shouldBe expectedBalanceBeforeMinor
            val call = Call(currency, amountMinor, reason).also(calls::add)
            val mode = modes[currency] ?: RewardDepositMode.SUCCESS
            if (mode == RewardDepositMode.REJECT) return CurrencyMoneyEvidence(false, true, balance(currency))
            balances[currency] = balance(currency) + if (mode == RewardDepositMode.PARTIAL) amountMinor / 2 else amountMinor
            if (mode != RewardDepositMode.PARTIAL) history[reason] = call
            if (mode == RewardDepositMode.THROW_AFTER_EFFECT) error("provider threw after applying")
            return CurrencyMoneyEvidence(mode == RewardDepositMode.SUCCESS, true, balance(currency))
        }
        override fun findTransaction(playerId: UUID, amountMinor: Long, reason: String, notBeforeMillis: Long): CompletableFuture<CurrencyTransactionEvidence> {
            requireNative()
            historyQueries += Call(currency, amountMinor, reason, notBeforeMillis)
            val found = historyAvailable && history[reason]?.let { it.currency == currency && it.amount == amountMinor } == true
            return CompletableFuture.completedFuture(CurrencyTransactionEvidence(if (found) "exact-history-id" else null, historyAvailable))
        }
        override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): CurrencyMoneyEvidence = error("withdraw is forbidden")
    }

    override fun balanceMinor(playerId: UUID): Long = error("Select a currency first")
    override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): CurrencyMoneyEvidence = error("Select a currency first")
    override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): CurrencyMoneyEvidence = error("withdraw is forbidden")
}
