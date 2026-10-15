package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.entity.Player
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GravePaymentsTest : FunSpec({
    test("post success charges once and a later post cancellation refunds once") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.prepareAndAdmit()

            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe true
            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe false
            fixture.payments.complete(fixture.player, fixture.grave, cancelled = true)
            fixture.pump()

            fixture.journal.loadAll() shouldBe emptyList()
            verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
            verify(exactly = 1) { fixture.economy.depositPlayer(fixture.player, PRICE) }
        }
    }

    test("a balance probe exception fails closed before the provider debit") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.prepareAndAdmit()
            var checks = 0
            every { fixture.economy.has(fixture.player, any<Double>()) } answers {
                if (++checks == 1) throw IllegalStateException("provider read timeout")
                true
            }

            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe false
            fixture.payments.complete(fixture.player, fixture.grave, cancelled = true)
            fixture.pump()

            fixture.journal.loadAll() shouldBe emptyList()
            fixture.feedback.last() shouldBe "payment-unavailable"
            verify(exactly = 0) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
        }
    }

    test("an admission balance probe exception does not create an intent or start native teleport") {
        Fixture().use { fixture ->
            fixture.start()
            every { fixture.economy.has(fixture.player, any<Double>()) } throws IllegalStateException("provider read timeout")

            fixture.payments.prepare(fixture.player, fixture.grave, PRICE, { true }) { fixture.starts++ }

            fixture.feedback.last() shouldBe "payment-unavailable"
            fixture.starts shouldBe 0
            fixture.journal.loadAll() shouldBe emptyList()
            verify(exactly = 0) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
        }
    }

    test("a canceled native pre-event with no post callback times out without a debit") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.payments.prepare(fixture.player, fixture.grave, PRICE, { true }) { fixture.starts++ }
            fixture.pump()
            fixture.starts shouldBe 1
            // Another listener canceled the pre-event, so our ignore-cancelled admission was not consumed.

            fixture.scheduler.advanceMs(120_000)
            fixture.pump()

            fixture.journal.loadAll() shouldBe emptyList()
            fixture.feedback.last() shouldBe "teleport-failed"
            verify(exactly = 0) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
        }
    }

    test("a native teleport accepted at pre but missing its post callback times out without a debit") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.prepareAndAdmit()

            fixture.scheduler.advanceMs(120_000)
            fixture.pump()

            fixture.journal.loadAll() shouldBe emptyList()
            fixture.feedback.last() shouldBe "teleport-failed"
            verify(exactly = 0) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
        }
    }

    test("a free quote invalidated after native pre is not charged or accepted") {
        Fixture().use { fixture ->
            fixture.start()
            var currentPrice = 0.0
            fixture.payments.prepare(fixture.player, fixture.grave, currentPrice, { currentPrice == 0.0 }) {
                fixture.starts++
                fixture.payments.admitNative(fixture.player, fixture.grave) shouldBe true
                currentPrice = PRICE
            }

            fixture.starts shouldBe 1
            fixture.payments.charge(fixture.player, fixture.grave, currentPrice, valid = false) shouldBe false
            fixture.payments.complete(fixture.player, fixture.grave, cancelled = true)
            fixture.pump()

            fixture.journal.loadAll() shouldBe emptyList()
            fixture.feedback.last() shouldBe "changed"
            verify(exactly = 0) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
        }
    }

    test("an ambiguous debit exception keeps its intent and is never retried") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.prepareAndAdmit()
            every { fixture.economy.withdrawPlayer(fixture.player, PRICE) } throws IllegalStateException("provider timeout")

            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe false
            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe false
            fixture.payments.complete(fixture.player, fixture.grave, cancelled = true)

            fixture.journal.loadAll() shouldHaveSize 1
            fixture.payments.prepare(fixture.player, fixture.grave, PRICE, { true }) {}
            fixture.feedback.last() shouldBe "payment-unavailable"
            verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }

            val restartScheduler = TestTaskScheduler()
            val restartFeedback = mutableListOf<String>()
            val restarted =
                GravePayments(
                    fixture.root,
                    { _, message -> restartFeedback += message },
                    scheduler = restartScheduler,
                    journalFactory = { fixture.journal },
                    economyProvider = { fixture.economy },
                    warning = { _, _ -> },
                )
            try {
                repeat(8) { restartScheduler.executeImmediate() }
                restarted.prepare(fixture.player, fixture.grave, PRICE, { true }) {}

                restartFeedback.last() shouldBe "payment-unavailable"
                verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, PRICE) }
            } finally {
                restarted.close()
                restartScheduler.close()
            }
        }
    }

    test("a failed rollback refund keeps the durable intent unresolved") {
        Fixture().use { fixture ->
            fixture.start()
            fixture.prepareAndAdmit()
            every { fixture.economy.depositPlayer(fixture.player, PRICE) } returns
                EconomyResponse(PRICE, 100.0, EconomyResponse.ResponseType.FAILURE, "provider rejected refund")

            fixture.payments.charge(fixture.player, fixture.grave, PRICE, valid = true) shouldBe true
            fixture.payments.complete(fixture.player, fixture.grave, cancelled = true)
            fixture.pump()

            fixture.journal.loadAll() shouldHaveSize 1
            fixture.feedback.last() shouldBe "payment-unavailable"
            verify(exactly = 1) { fixture.economy.depositPlayer(fixture.player, PRICE) }
        }
    }

    test("shutdown racing a durable write acknowledges it after the write finishes") {
        val root = Files.createTempDirectory("arc-grave-payments-close-")
        val durable = DurableGravePaymentJournal(root)
        val blocking = BlockingCommitJournal(durable)
        val driver = TestTaskScheduler()
        val rejectingScheduler = RejectingAsyncScheduler(driver)
        Fixture(root, blocking, driver, rejectingScheduler).use { fixture ->
            fixture.start()
            fixture.payments.prepare(fixture.player, fixture.grave, PRICE, { true }) { fixture.starts++ }

            val workerFailure = AtomicReference<Throwable?>()
            val commitWorker = Thread {
                try {
                    fixture.scheduler.executeImmediate()
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                }
            }
            commitWorker.start()
            try {
                check(blocking.commitStarted.await(5, TimeUnit.SECONDS)) { "durable commit did not reach its test gate" }

                rejectingScheduler.disabled = true
                fixture.payments.close()
                // If close acknowledges before the write, this runs the premature ALREADY_ACKNOWLEDGED
                // result before the record exists, reproducing the lost-intent race deterministically.
                fixture.pump()
            } finally {
                blocking.releaseCommit.countDown()
                commitWorker.join(5_000)
            }
            commitWorker.isAlive shouldBe false
            workerFailure.get() shouldBe null
            fixture.pump()

            durable.loadAll() shouldBe emptyList()
            rejectingScheduler.rejectedAsyncSchedules shouldBe 0
            fixture.starts shouldBe 0
            fixture.feedback shouldBe emptyList()
        }
    }

    test("shutdown drains a committed unpaid intent without scheduling after disable") {
        val driver = TestTaskScheduler()
        val rejectingScheduler = RejectingAsyncScheduler(driver)
        Fixture(scheduler = driver, paymentScheduler = rejectingScheduler).use { fixture ->
            fixture.start()
            fixture.payments.prepare(fixture.player, fixture.grave, PRICE, { true }) { fixture.starts++ }
            fixture.pump()
            fixture.journal.loadAll() shouldHaveSize 1

            rejectingScheduler.disabled = true
            fixture.payments.close()

            fixture.journal.loadAll() shouldBe emptyList()
            rejectingScheduler.rejectedAsyncSchedules shouldBe 0
            fixture.starts shouldBe 1
        }
    }
})

private class Fixture(
    val root: Path = Files.createTempDirectory("arc-grave-payments-test-"),
    journal: GravePaymentJournal = DurableGravePaymentJournal(root),
    val scheduler: TestTaskScheduler = TestTaskScheduler(),
    private val paymentScheduler: TaskScheduler = scheduler,
) : AutoCloseable {
    val playerId = UUID.randomUUID()
    val grave = UUID.randomUUID()
    val player: Player = mockk(relaxed = true)
    val economy: Economy = mockk(relaxed = true)
    val feedback = mutableListOf<String>()
    var starts = 0

    val journal: GravePaymentJournal = journal
    val payments =
        GravePayments(
            root,
            { _, message -> feedback += message },
            scheduler = paymentScheduler,
            journalFactory = { this.journal },
            economyProvider = { economy },
            warning = { _, _ -> },
        )

    init {
        every { player.uniqueId } returns playerId
        every { player.isOnline } returns true
        every { economy.has(player, any<Double>()) } returns true
        every { economy.withdrawPlayer(player, any<Double>()) } answers {
            EconomyResponse(secondArg<Double>(), 100.0 - secondArg<Double>(), EconomyResponse.ResponseType.SUCCESS, null)
        }
        every { economy.depositPlayer(player, any<Double>()) } answers {
            EconomyResponse(secondArg<Double>(), 100.0 + secondArg<Double>(), EconomyResponse.ResponseType.SUCCESS, null)
        }
    }

    fun start() = pump()

    fun prepareAndAdmit() {
        payments.prepare(player, grave, PRICE, { true }) { starts++ }
        pump()
        starts shouldBe 1
        payments.admitNative(player, grave) shouldBe true
    }

    fun pump() {
        repeat(8) { scheduler.executeImmediate() }
    }

    override fun close() {
        payments.close()
        paymentScheduler.close()
        root.toFile().deleteRecursively()
    }
}

private class RejectingAsyncScheduler(private val delegate: TestTaskScheduler) : TaskScheduler {
    @Volatile
    var disabled = false

    var rejectedAsyncSchedules = 0
        private set

    override fun runAsync(task: Runnable): ScheduledTask {
        if (disabled) {
            rejectedAsyncSchedules++
            throw IllegalStateException("plugin disabled")
        }
        return delegate.runAsync(task)
    }

    override fun runSync(task: Runnable): ScheduledTask = delegate.runSync(task)

    override fun runLater(delayTicks: Long, task: Runnable): ScheduledTask = delegate.runLater(delayTicks, task)

    override fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask {
        if (disabled) {
            rejectedAsyncSchedules++
            throw IllegalStateException("plugin disabled")
        }
        return delegate.runLaterAsync(delayTicks, task)
    }

    override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        delegate.runTimer(delayTicks, periodTicks, task)

    override fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask {
        if (disabled) {
            rejectedAsyncSchedules++
            throw IllegalStateException("plugin disabled")
        }
        return delegate.runTimerAsync(delayTicks, periodTicks, task)
    }

    override fun cancelAll() = delegate.cancelAll()

    override fun close() = delegate.close()
}

private class BlockingCommitJournal(private val delegate: GravePaymentJournal) : GravePaymentJournal by delegate {
    val commitStarted = CountDownLatch(1)
    val releaseCommit = CountDownLatch(1)

    override fun commit(recordId: String, intent: GravePayments.Intent): GravePayments.Intent {
        commitStarted.countDown()
        check(releaseCommit.await(5, TimeUnit.SECONDS)) { "test did not release blocked durable commit" }
        return delegate.commit(recordId, intent)
    }
}

private const val PRICE = 12.5
