package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class InvestigationAsyncRuntimeTest : StringSpec({
    "journal acknowledgement keeps duplicate admission held through native resumption while main heartbeats progress" {
        AsyncInvestigationFixture().use { f ->
            f.startReady()
            val active = f.journal.block(InvestigationStatus.ACTIVE)
            val replies = mutableListOf<InvestigationStartResult>()
            var nested: InvestigationStartResult? = null
            val started = f.runtime.startCase(f.player, false) {
                f.main()
                replies += it
                f.runtime.startCase(f.player, false) { busy -> nested = busy }
            }
            f.pump { active.entered.count == 0L }
            f.wallet.withdrawals shouldBe 1
            started.isDone shouldBe false
            replies shouldBe emptyList()
            f.runtime.current(f.player)?.status shouldBe InvestigationStatus.WITHDRAWAL_STARTED
            val reads = f.journal.reads.get()
            repeat(1_000) { f.runtime.current(f.player); f.runtime.latest(f.player) }
            f.journal.reads.get() shouldBe reads
            val heartbeat = f.heartbeats
            repeat(20) { f.scheduler.tick() }
            f.heartbeats - heartbeat shouldBe 20
            f.runtime.startCase(f.player, false) { replies += it }.join() shouldBe InvestigationStartResult.Busy
            active.release.countDown()
            f.pump { started.isDone }
            started.join()::class shouldBe InvestigationStartResult.Started::class
            nested shouldBe InvestigationStartResult.Busy
            f.wallet.withdrawals shouldBe 1
            val record = (started.join() as InvestigationStartResult.Started).record
            for (witness in record.case.witnesses().take(3)) {
                val clue = f.runtime.collect(f.player, witness) { f.main() }
                f.pump { clue.isDone }
                clue.join()::class shouldBe InvestigationClueResult.Evidence::class
            }
            val paid = f.journal.block(InvestigationStatus.COMPLETED)
            val verdict = f.runtime.submit(f.player, record.case.verdict) { f.main() }
            f.pump { paid.entered.count == 0L }
            f.wallet.deposits shouldBe 1
            verdict.isDone shouldBe false
            f.runtime.submit(f.player, record.case.verdict) { }.join() shouldBe InvestigationVerdictResult.Busy
            paid.release.countDown()
            f.pump { verdict.isDone }
            verdict.join()::class shouldBe InvestigationVerdictResult.Success::class
            f.runtime.latest(f.player)?.status shouldBe InvestigationStatus.COMPLETED
            f.wallet.balance shouldBe 120_000L
        }
    }

    "expiry is coalesced on the worker and published only after its durable acknowledgement" {
        AsyncInvestigationFixture().use { f ->
            f.startReady()
            val start = f.runtime.startCase(f.player, false) { }
            f.pump { start.isDone }
            val record = (start.join() as InvestigationStartResult.Started).record
            val failed = f.journal.block(InvestigationStatus.FAILED)
            f.now.set(requireNotNull(record.expiresAt) + 1)
            repeat(1_000) { f.runtime.current(f.player) }
            f.pump { failed.entered.count == 0L }
            f.runtime.latest(f.player)?.status shouldBe InvestigationStatus.ACTIVE
            val before = f.journal.reads.get()
            repeat(1_000) { f.runtime.current(f.player) }
            f.journal.reads.get() shouldBe before
            failed.release.countDown()
            f.pump { f.runtime.latest(f.player)?.status == InvestigationStatus.FAILED }
            f.runtime.current(f.player) shouldBe null
            f.wallet.withdrawals shouldBe 1
            f.wallet.deposits shouldBe 0
        }
    }

    "close cancels the queued native debit and drains the accepted journal operation without retrying money" {
        AsyncInvestigationFixture().use { f ->
            f.startReady()
            val start = f.runtime.startCase(f.player, false) { error("Closed generation cannot resume a native handler") }
            f.pump { f.journal.committed.get()?.status == InvestigationStatus.WITHDRAWAL_STARTED }
            f.await { f.scheduler.pendingCount() > 0 }
            f.wallet.withdrawals shouldBe 0
            f.runtime.close()
            f.runtime.close()
            f.runtime.drained().get(5, TimeUnit.SECONDS)
            start.isCompletedExceptionally shouldBe true
            f.scheduler.tick(10)
            f.wallet.withdrawals shouldBe 0
            f.journal.committed.get()?.status shouldBe InvestigationStatus.WITHDRAWAL_STARTED
        }
    }

    "recovery and the predecessor drain wait actual history and never repeat a provider mutation" {
        AsyncInvestigationFixture(recoverWithdrawal = true).use { f ->
            val history = CompletableFuture<InvestigationProviderEvidence>()
            f.wallet.history = history
            f.runtime.start()
            f.pump { f.wallet.historyStarted.count == 0L }
            f.runtime.readiness().isDone shouldBe false
            f.runtime.available shouldBe false
            val heartbeat = f.heartbeats
            f.scheduler.tick(20)
            f.heartbeats - heartbeat shouldBe 20
            f.runtime.close()
            f.runtime.drained().isDone shouldBe false
            history.complete(InvestigationProviderEvidence("proven-original-fee", true))
            f.runtime.drained().get(5, TimeUnit.SECONDS)
            f.journal.committed.get()?.status shouldBe InvestigationStatus.ACTIVE
            f.wallet.withdrawals shouldBe 0
            f.wallet.deposits shouldBe 0
        }
    }

    "a replaced player identity cannot execute the old accepted debit intent" {
        AsyncInvestigationFixture().use { f ->
            f.startReady()
            var samePlayer = true
            val start = f.runtime.startCase(f.player, false, isCurrentPlayer = { f.main(); samePlayer }) { }
            f.pump { f.journal.committed.get()?.status == InvestigationStatus.WITHDRAWAL_STARTED }
            f.await { f.scheduler.pendingCount() > 0 }
            samePlayer = false
            f.pump { start.isDone }
            start.isCompletedExceptionally shouldBe true
            f.wallet.withdrawals shouldBe 0
        }
    }
})

private class AsyncInvestigationFixture(recoverWithdrawal: Boolean = false) : AutoCloseable {
    private val owner = Thread.currentThread()
    val player: UUID = UUID.randomUUID()
    val now = AtomicLong(1_000)
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val worker = BoundedAsyncExecutor("investigation-test", 1, 8)
    private val root = Files.createTempDirectory("arc-investigation-async-")
    val journal = GuardedInvestigationJournal(owner)
    val wallet = NativeTestInvestigationWallet(owner)
    var heartbeats = 0
        private set
    val runtime: InvestigationAsyncRuntime

    init {
        Files.createDirectories(root.resolve("modules"))
        val bundled = requireNotNull(javaClass.getResourceAsStream("/modules/investigations.yml")).bufferedReader().use { it.readText() }
        Files.writeString(root.resolve("modules/investigations.yml"), bundled.replaceFirst("enabled: false", "enabled: true"))
        scheduler.runTimer(1, 1, Runnable { main(); heartbeats++ })
        runtime = InvestigationAsyncRuntime(tasks, worker,
            load = { _ ->
                check(Thread.currentThread() !== owner)
                val catalog = bundledInvestigationCatalogForTest
                InvestigationRuntimeConfig(InvestigationConfig(Config(root, "modules/investigations.yml")).validated(catalog.witnessKeys), catalog)
            },
            journalFactory = {
                journal.initialize(FileInvestigationJournal(root))
                if (recoverWithdrawal) {
                    val prepared = InvestigationJournalRecord(UUID.randomUUID().toString(), player.toString(),
                        investigationCaseGeneratorForTest().generate(Random(42)), 10_000, 30_000, createdAt = now.get())
                    journal.persist(prepared)
                    journal.persist(prepared.copy(status = InvestigationStatus.WITHDRAWAL_STARTED,
                        updatedAt = now.get() + 1, withdrawalStartedAt = now.get() + 1, feeBalanceBeforeMinor = 100_000))
                }
                journal
            }, wallet = wallet, currentGeneration = { true }, canWithdraw = { main(); true },
            onReady = { main() }, onFailure = { _, _ -> main() }, onManualReview = { check(Thread.currentThread() !== owner) },
            clock = now::get)
    }

    fun main() { check(Thread.currentThread() === owner) { "Native operation escaped the owning thread" } }
    fun startReady() { runtime.start(); pump { runtime.readiness().isDone }; runtime.readiness().join() }
    fun pump(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) { scheduler.tick(); Thread.sleep(1) }
        check(done()) { "Async investigation did not reach the expected barrier" }
    }
    fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(1)
        check(done()) { "Async investigation did not enqueue its native operation" }
    }
    override fun close() {
        journal.releaseAll()
        wallet.history.complete(InvestigationProviderEvidence(null, false))
        runtime.close()
        runtime.drained().get(5, TimeUnit.SECONDS)
    }
}

private class GuardedInvestigationJournal(private val main: Thread) : InvestigationJournal {
    class Barrier(val status: InvestigationStatus) { val entered = CountDownLatch(1); val release = CountDownLatch(1) }
    private lateinit var delegate: InvestigationJournal
    private val waiting = AtomicReference<Barrier?>()
    private val barriers = mutableListOf<Barrier>()
    val reads = AtomicInteger()
    val committed = AtomicReference<InvestigationJournalRecord?>()
    fun initialize(journal: InvestigationJournal) { checkWorker(); delegate = journal }
    fun block(status: InvestigationStatus) = Barrier(status).also { barriers += it; waiting.set(it) }
    fun releaseAll() { barriers.forEach { it.release.countDown() } }
    override fun records(): List<InvestigationJournalRecord> { checkWorker(); reads.incrementAndGet(); return delegate.records() }
    override fun persist(record: InvestigationJournalRecord): Boolean {
        checkWorker()
        val barrier = waiting.get()
        if (barrier?.status == record.status && waiting.compareAndSet(barrier, null)) {
            barrier.entered.countDown()
            check(barrier.release.await(5, TimeUnit.SECONDS))
        }
        return delegate.persist(record).also { if (it) committed.set(record) }
    }
    private fun checkWorker() = check(Thread.currentThread() !== main) { "Journal operation ran on the owning thread" }
}

private class NativeTestInvestigationWallet(private val main: Thread) : InvestigationWallet {
    override val available: Boolean get() { native(); return true }
    var balance = 100_000L
    var withdrawals = 0
    var deposits = 0
    var history = CompletableFuture.completedFuture(InvestigationProviderEvidence(null, false))
    val historyStarted = CountDownLatch(1)
    override fun balanceMinor(playerId: UUID): Long { native(); return balance }
    override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence {
        native(); check(balance == expectedBalanceBeforeMinor); withdrawals++; balance -= amountMinor
        return InvestigationMoneyEvidence(true, true, balance)
    }
    override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence {
        native(); check(balance == expectedBalanceBeforeMinor); deposits++; balance += amountMinor
        return InvestigationMoneyEvidence(true, true, balance)
    }
    override fun findTransaction(playerId: UUID, amountMinor: Long, reason: String, notBeforeMillis: Long): CompletableFuture<InvestigationProviderEvidence> {
        check(Thread.currentThread() !== main); historyStarted.countDown(); return history
    }
    private fun native() = check(Thread.currentThread() === main) { "Wallet mutation escaped native affinity" }
}
