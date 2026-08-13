package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ContractSubmissionCoordinatorTest : StringSpec({
    val definition =
        ResourceContractDefinition(
            id = "road_stone",
            displayName = "Камень для тракта",
            itemKey = "minecraft:stone",
            funding = ContractFunding.SERVER_ENVELOPE,
            windowStartsAt = 1_000L,
            windowEndsAt = 20_000L,
            payoutMinorPerUnit = 250L,
            budgetMinor = 25_000L,
            targetQuantity = 100L,
            perPlayerQuantityCap = 40L,
            minSubmissionQuantity = 4,
            maxSubmissionQuantity = 32,
        )

    "persists every intent before removing items and paying exactly once" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(true, 12_000L),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            val outcome = coordinator.submit(definition, "submission-1", "player-1", 8)

            (outcome as ContractSubmissionOutcome.Committed).receipt.payoutMinor shouldBe 2_000L
            payment.depositCalls shouldBe 1
            persistence.state.acceptedQuantity shouldBe 8L
            persistence.journals.getValue("submission-1").status shouldBe
                ContractSubmissionJournalStatus.CONTRACT_COMMITTED
            events.shouldContainExactly(
                "journal:prepared",
                "journal:item_removal_started",
                "inventory:remove",
                "journal:items_escrowed",
                "payment:balance",
                "journal:payment_started",
                "payment:deposit",
                "journal:paid",
                "contract:1",
                "journal:contract_committed",
            )
        }
    }

    "refunds exact escrow after an explicit unchanged-balance provider failure" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(false, 10_000L, "Maximum balance"),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            val outcome = coordinator.submit(definition, "submission-2", "player-1", 8)

            outcome shouldBe ContractSubmissionOutcome.Refunded("submission-2", "provider_rejected")
            persistence.state shouldBe ResourceContractState.empty(definition)
            persistence.journals.getValue("submission-2").status shouldBe ContractSubmissionJournalStatus.REFUNDED
            preparedInventory.restoreCalls shouldBe 1
            events.shouldContainExactly(
                "journal:prepared",
                "journal:item_removal_started",
                "inventory:remove",
                "journal:items_escrowed",
                "payment:balance",
                "journal:payment_started",
                "payment:deposit",
                "journal:payment_failed",
                "journal:refund_started",
                "inventory:restore",
                "journal:refunded",
            )
        }
    }

    "halts an ambiguous provider outcome without retrying payout or releasing quota" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(null, 10_500L),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-3", "player-1", 8) shouldBe
                ContractSubmissionOutcome.ManualReview("submission-3")

            payment.depositCalls shouldBe 1
            preparedInventory.restoreCalls shouldBe 0
            persistence.state shouldBe ResourceContractState.empty(definition)
            val review = persistence.journals.getValue("submission-3")
            review.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
            review.quotaReservation() shouldBe ContractQuotaReservation("submission-3", "player-1", 8L, 2_000L)
        }
    }

    "cancels a durable intent when inventory prevalidation proves no slot changed" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory =
                FakePreparedInventory(
                    definition.itemKey,
                    8,
                    events,
                    removeResult = ContractInventoryMutation.NotPerformed("slot_changed"),
                )
            val payment = FakePayment(10_000L, ContractPaymentEvidence(true, 12_000L), events)
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-4", "player-1", 8) shouldBe
                ContractSubmissionOutcome.Cancelled("submission-4", "inventory_changed_before_remove")

            payment.depositCalls shouldBe 0
            persistence.journals.getValue("submission-4").quotaReservation() shouldBe null
        }
    }

    "refunds without starting a payout when the provider balance is unavailable" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment = FakePayment(null, ContractPaymentEvidence(true, 12_000L), events)
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-5", "player-1", 8) shouldBe
                ContractSubmissionOutcome.Refunded("submission-5", "provider_balance_unavailable")

            payment.depositCalls shouldBe 0
            persistence.journals.getValue("submission-5").paymentStartedAt shouldBe null
            persistence.journals.getValue("submission-5").status shouldBe ContractSubmissionJournalStatus.REFUNDED
        }
    }

    "recovers a crash after proven payout by idempotently committing state" {
        val plan =
            ResourceContractEngine.plan(
                definition,
                ResourceContractState.empty(definition),
                "paid-crash",
                "player-1",
                8,
                1_500L,
            ) as ContractSubmissionPlan.Accepted
        val prepared =
            ContractSubmissionJournalEngine.prepare(
                definition,
                plan,
                listOf(EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(1, 2, 3))),
                1_500L,
            )
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared, 1_501L),
                1_502L,
            )
        val paid =
            ContractSubmissionJournalEngine.confirmPaid(
                ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L),
                12_000L,
                null,
                1_504L,
            )

        val first =
            ContractSubmissionRecoveryEngine.recoverPaid(
                definition,
                ResourceContractState.empty(definition),
                paid,
                2_000L,
            )
        first.commit.changed shouldBe true
        first.commit.state.acceptedQuantity shouldBe 8L
        first.journal.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED

        val replay = ContractSubmissionRecoveryEngine.recoverPaid(definition, first.commit.state, paid, 2_001L)
        replay.commit.changed shouldBe false
        replay.commit.state shouldBe first.commit.state
        replay.journal.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED
    }
})

private class FakePersistence(
    definition: ResourceContractDefinition,
    private val events: MutableList<String>,
) : ContractSubmissionPersistence {
    var state = ResourceContractState.empty(definition)
    val journals = linkedMapOf<String, ContractSubmissionJournalRecord>()

    override fun contractState(definition: ResourceContractDefinition): ResourceContractState = state

    override fun journalRecords(): List<ContractSubmissionJournalRecord> = journals.values.toList()

    override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
        events += "journal:${record.status.label}"
        journals[record.submissionId] = record
    }

    override suspend fun persistContract(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
    ) {
        state.validatedAgainst(definition)
        this.state = state
        events += "contract:${state.revision}"
    }
}

private class FakeInventory(
    private val prepared: PreparedContractInventory?,
) : ContractInventoryGateway {
    override suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
    ): PreparedContractInventory? = prepared
}

private class FakePreparedInventory(
    itemKey: String,
    quantity: Int,
    private val events: MutableList<String>,
    private val removeResult: ContractInventoryMutation = ContractInventoryMutation.Confirmed,
    private val restoreResult: ContractInventoryMutation = ContractInventoryMutation.Confirmed,
) : PreparedContractInventory {
    override val payloads =
        listOf(
            EscrowedItemPayload.capture(
                itemKey,
                quantity,
                byteArrayOf(10, 0, 3, 1, 2, 3, quantity.toByte()),
            ),
        )
    var restoreCalls = 0

    override suspend fun removeExact(): ContractInventoryMutation {
        events += "inventory:remove"
        return removeResult
    }

    override suspend fun restoreExact(): ContractInventoryMutation {
        restoreCalls += 1
        events += "inventory:restore"
        return restoreResult
    }
}

private class FakePayment(
    private val balance: Long?,
    private val evidence: ContractPaymentEvidence,
    private val events: MutableList<String>,
) : ContractPaymentGateway {
    var depositCalls = 0

    override suspend fun balanceMinor(playerId: String): Long? {
        events += "payment:balance"
        return balance
    }

    override suspend fun deposit(
        playerId: String,
        amountMinor: Long,
        reason: String,
    ): ContractPaymentEvidence {
        depositCalls += 1
        events += "payment:deposit"
        return evidence
    }
}

private fun tickingClock(): () -> Long {
    var now = 1_500L
    return { now++ }
}
