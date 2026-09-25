package ru.arc.itemlore

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.entity.Player
import ru.arc.persistence.DurableAcknowledgementOutcome
import java.util.ArrayDeque
import java.util.UUID

class ItemLorePaymentsTest : FunSpec({
    test("journal construction and load both start on the async queue") {
        val tasks = QueuedTasks()
        val journal = MemoryJournal()
        var constructed = false
        val payments =
            ItemLorePayments(
                journalFactory = { constructed = true; journal },
                economyProvider = { null },
                playerLookup = { null },
                tasks = tasks,
                isMainThread = { true },
                warning = { _, _ -> },
            )

        payments.start()
        constructed shouldBe false
        payments.isReady shouldBe false
        tasks.runAsync()
        constructed shouldBe true
        payments.isReady shouldBe false
        tasks.runSync()
        payments.isReady shouldBe true
    }

    test("delayed journal commit rejects duplicate delivery and charges once") {
        val fixture = Fixture(balance = 99.0)
        fixture.start()
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()
        var applied = 0

        fixture.payments.submit(fixture.request(), { true }, { applied++ }, outcomes::add)
        fixture.tasks.runSync()
        fixture.journal.commits shouldBe 0

        fixture.payments.submit(fixture.request(), { true }, { applied++ }, outcomes::add)
        fixture.tasks.runSync()
        outcomes shouldContain ItemLorePaymentOutcome.BUSY_OR_DUPLICATE
        fixture.economy.let { verify(exactly = 0) { it.withdrawPlayer(fixture.player, 1.25) } }

        fixture.tasks.runAsync() // durable pre-debit record
        fixture.tasks.runSync() // revalidate and one-shot debit/mutation
        applied shouldBe 1
        fixture.tasks.runAsync() // exact acknowledgement
        fixture.tasks.runSync()

        outcomes.last() shouldBe ItemLorePaymentOutcome.SUCCESS
        fixture.journal.records shouldBe emptyMap()
        verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, 1.25) }
    }

    test("insufficient balance does not commit, debit, or mutate") {
        val fixture = Fixture(balance = 0.50)
        fixture.start()
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()
        var applied = false

        fixture.payments.submit(fixture.request(priceMinor = 125), { true }, { applied = true }, outcomes::add)
        fixture.tasks.runSync()

        outcomes shouldBe listOf(ItemLorePaymentOutcome.INSUFFICIENT_FUNDS)
        fixture.journal.commits shouldBe 0
        applied shouldBe false
        verify(exactly = 0) { fixture.economy.withdrawPlayer(any<Player>(), any<Double>()) }
    }

    test("domain cancellation during durable prepare acknowledges without charging") {
        val fixture = Fixture(balance = 99.0)
        fixture.start()
        var valid = true
        var applied = false
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()

        fixture.payments.submit(fixture.request(), { valid }, { applied = true }, outcomes::add)
        fixture.tasks.runSync() // reserve and queue durable prepare
        fixture.tasks.runAsync() // commit the intent
        valid = false // session/NPC/slot became stale while disk I/O was pending
        fixture.tasks.runSync() // revalidate, then queue exact ack
        fixture.tasks.runAsync()
        fixture.tasks.runSync()

        outcomes shouldBe listOf(ItemLorePaymentOutcome.CANCELLED)
        fixture.journal.records shouldBe emptyMap()
        applied shouldBe false
        verify(exactly = 0) { fixture.economy.withdrawPlayer(any<Player>(), any<Double>()) }
    }

    test("close invalidates an intent still waiting for its durable prepare") {
        val fixture = Fixture(balance = 99.0)
        fixture.start()
        var applied = false
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()

        fixture.payments.submit(fixture.request(), { true }, { applied = true }, outcomes::add)
        fixture.tasks.runSync()
        fixture.payments.close()
        fixture.tasks.runAsync()
        fixture.tasks.runSync() // shutdown is observed before the Vault boundary
        fixture.tasks.runAsync()
        fixture.tasks.runSync()

        outcomes shouldBe listOf(ItemLorePaymentOutcome.CANCELLED)
        fixture.journal.records shouldBe emptyMap()
        applied shouldBe false
        verify(exactly = 0) { fixture.economy.withdrawPlayer(any<Player>(), any<Double>()) }
    }

    test("ambiguous withdrawal keeps its durable record and locks the player after restart") {
        val fixture = Fixture(balance = 99.0)
        fixture.start()
        every { fixture.economy.withdrawPlayer(fixture.player, 1.25) } throws IllegalStateException("provider timeout")
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()

        fixture.payments.submit(fixture.request(), { true }, {}, outcomes::add)
        fixture.tasks.runSync()
        fixture.tasks.runAsync() // durable pre-debit record
        fixture.tasks.runSync() // provider call throws after the irreversible boundary

        outcomes shouldBe listOf(ItemLorePaymentOutcome.OUTCOME_UNKNOWN)
        fixture.journal.records.size shouldBe 1
        verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, 1.25) }
        fixture.logs.any {
            it.contains("op=${fixture.journal.records.values.single().operationId}") &&
                it.contains("player=${fixture.playerId}") &&
                it.contains("phase=withdraw") &&
                it.contains("cause=IllegalStateException: provider timeout")
        } shouldBe true

        val restarted = fixture.newPayments()
        restarted.start()
        fixture.tasks.runAsync()
        fixture.tasks.runSync()
        restarted.isReady shouldBe true
        val afterRestart = mutableListOf<ItemLorePaymentOutcome>()
        restarted.submit(fixture.request(operationId = UUID.randomUUID()), { true }, {}, afterRestart::add)
        fixture.tasks.runSync()

        afterRestart shouldBe listOf(ItemLorePaymentOutcome.OUTCOME_UNKNOWN)
        fixture.journal.records.size shouldBe 1
        verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, 1.25) }
        fixture.logs.any {
            it.contains("player=${fixture.playerId}") && it.contains("phase=recovery") && it.contains("cause=unresolved_record_found")
        } shouldBe true
    }

    test("item mutation exception keeps the paid operation unresolved") {
        val fixture = Fixture(balance = 99.0)
        fixture.start()
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()

        fixture.payments.submit(fixture.request(), { true }, { error("inventory changed") }, outcomes::add)
        fixture.tasks.runSync()
        fixture.tasks.runAsync()
        fixture.tasks.runSync()

        outcomes shouldBe listOf(ItemLorePaymentOutcome.OUTCOME_UNKNOWN)
        fixture.journal.records.size shouldBe 1
        verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, 1.25) }
        fixture.logs.any { it.contains("phase=apply") && it.contains("cause=IllegalStateException: inventory changed") } shouldBe true
    }

    test("acknowledgement mismatch keeps the player locked") {
        val fixture = Fixture(balance = 99.0)
        fixture.journal.acknowledgementMismatch = true
        fixture.start()
        val outcomes = mutableListOf<ItemLorePaymentOutcome>()

        fixture.payments.submit(fixture.request(), { true }, {}, outcomes::add)
        fixture.tasks.runSync()
        fixture.tasks.runAsync()
        fixture.tasks.runSync()
        fixture.tasks.runAsync()
        fixture.tasks.runSync()

        outcomes shouldBe listOf(ItemLorePaymentOutcome.OUTCOME_UNKNOWN)
        fixture.journal.records.size shouldBe 1
        verify(exactly = 1) { fixture.economy.withdrawPlayer(fixture.player, 1.25) }
        fixture.logs.any { it.contains("phase=acknowledge") && it.contains("content_mismatch") } shouldBe true
    }
}) {
    private class Fixture(balance: Double) {
        val tasks = QueuedTasks()
        val playerId = UUID.randomUUID()
        val player: Player = mockk(relaxed = true)
        val economy: Economy = mockk(relaxed = true)
        val journal = MemoryJournal()
        val logs = mutableListOf<String>()
        val payments = newPayments()

        init {
            every { player.uniqueId } returns playerId
            every { player.isOnline } returns true
            every { economy.getBalance(player) } returns balance
            every { economy.has(player, any<Double>()) } answers { balance >= secondArg<Double>() }
            every { economy.withdrawPlayer(player, any<Double>()) } answers {
                EconomyResponse(secondArg<Double>(), balance - secondArg<Double>(), EconomyResponse.ResponseType.SUCCESS, null)
            }
        }

        fun newPayments() =
            ItemLorePayments(
                journal = journal,
                economyProvider = { economy },
                playerLookup = { id -> player.takeIf { id == playerId } },
                tasks = tasks,
                isMainThread = { true },
                clockMillis = { 1_000L },
                warning = { message, _ -> logs += message },
            )

        fun start() {
            payments.start()
            tasks.runAsync()
            tasks.runSync()
            payments.isReady shouldBe true
        }

        fun request(
            operationId: UUID = UUID.randomUUID(),
            priceMinor: Long = 125,
        ) = ItemLorePaymentRequest(
            operationId = operationId,
            playerId = playerId,
            slot = 4,
            originalItemBytes = byteArrayOf(1, 2, 3),
            replacementItemBytes = byteArrayOf(3, 2, 1),
            priceMinor = priceMinor,
        )
    }

    private class QueuedTasks : ItemLorePaymentTasks {
        private val async = ArrayDeque<() -> Unit>()
        private val sync = ArrayDeque<() -> Unit>()

        override fun async(task: () -> Unit) {
            async.addLast(task)
        }

        override fun sync(task: () -> Unit) {
            sync.addLast(task)
        }

        fun runAsync() = async.removeFirst().invoke()

        fun runSync() = sync.removeFirst().invoke()
    }

    private class MemoryJournal : ItemLorePaymentJournal {
        val records = linkedMapOf<String, ItemLorePaymentJournalRecord>()
        var commits = 0
        var acknowledgementMismatch = false

        override fun loadAll(): List<ItemLorePaymentJournalRecord> = records.values.toList()

        override fun commit(record: ItemLorePaymentJournalRecord): ItemLorePaymentJournalRecord {
            commits++
            records[record.operationId] = record
            return record
        }

        override fun acknowledgeExactly(record: ItemLorePaymentJournalRecord): DurableAcknowledgementOutcome {
            if (acknowledgementMismatch) return DurableAcknowledgementOutcome.CONTENT_MISMATCH
            val current = records[record.operationId] ?: return DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
            if (!record.sameContent(current)) return DurableAcknowledgementOutcome.CONTENT_MISMATCH
            records.remove(record.operationId)
            return DurableAcknowledgementOutcome.ACKNOWLEDGED
        }
    }
}
