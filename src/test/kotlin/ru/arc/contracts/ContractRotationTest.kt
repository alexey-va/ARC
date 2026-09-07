package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ContractRotationTest : StringSpec({
    val start = Instant.parse("2026-09-07T00:00:00Z").toEpochMilli()
    val definition = ResourceContractDefinition(
        "weekly_coal", "Уголь", "minecraft:coal", ContractFunding.SERVER_ENVELOPE,
        start, start + ContractRotation.WEEK_MILLIS, 125, 100_000, 1_000, 500,
        dynamicPricing = true, weeklyRecurring = true,
    )
    fun plan(id: String = "submission-one") = ResourceContractEngine.plan(
        definition, ResourceContractState.empty(definition), id, "player-one", 8, start + 1_000,
    ) as ContractSubmissionPlan.Accepted
    fun journal() = ContractSubmissionJournalEngine.prepare(
        definition, plan(), listOf(EscrowedItemPayload.capture("minecraft:coal", 8, byteArrayOf(1))), start + 2_000,
    )

    "weekly rotation is stable on reopen and changes exactly at Monday UTC" {
        ContractRotation.at(definition, start - 1) shouldBe definition
        ContractRotation.at(definition, start + ContractRotation.WEEK_MILLIS - 1) shouldBe definition
        val next = ContractRotation.at(definition, start + ContractRotation.WEEK_MILLIS)
        next.windowStartsAt shouldBe start + ContractRotation.WEEK_MILLIS
        next.windowEndsAt shouldBe start + 2 * ContractRotation.WEEK_MILLIS
        ContractRotation.at(definition, start + 20 * ContractRotation.WEEK_MILLIS + 123).windowStartsAt shouldBe
            start + 20 * ContractRotation.WEEK_MILLIS
    }

    "quote binds player quantity total revision age and week" {
        val plan = plan()
        val quote = ContractSubmissionQuote(definition.id, start, plan.playerId, 8, plan.payoutMinor, 0, plan.plannedAt)
        quote.matches(definition, plan, plan.plannedAt + 30_000) shouldBe true
        quote.matches(definition, plan, plan.plannedAt + 30_001) shouldBe false
        quote.matches(definition, plan, plan.plannedAt - 1) shouldBe false
        quote.matches(definition, plan.copy(playerId = "another"), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(acceptedQuantity = 7), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(payoutMinor = plan.payoutMinor + 1), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(expectedRevision = 1), plan.plannedAt) shouldBe false
        quote.matches(definition, plan, definition.windowEndsAt) shouldBe false
    }

    "journal retains exact quote inputs and rejects a tampered total even within price bounds" {
        val record = journal()
        val restored = Gson().fromJson(Gson().toJson(record), ContractSubmissionJournalRecord::class.java).validated()
        restored shouldBe record
        shouldThrow<IllegalArgumentException> { record.copy(payoutMinor = record.payoutMinor + 1) }
        shouldThrow<IllegalArgumentException> { record.copy(quoteSupplyBefore = null) }
        restored.definitionSnapshot shouldBe definition
        ResourceContractRecord.empty(definition).definitionSnapshot shouldBe definition
    }

    "network weekly budget counts retired orders and held payouts once" {
        val prepared = journal()
        val held = ContractSubmissionJournalEngine.beginItemRemoval(prepared, start + 3_000)
        val committed = ResourceContractEngine.commit(definition, ResourceContractState.empty(definition), plan(), start + 4_000)
        val state = ResourceContractRecord(ResourceContractRecord.stateId(definition.id, start), committed.state, definition)
        ContractRotation.remainingBudget(100_000, emptyList(), listOf(held), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), listOf(held), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), emptyList(), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), emptyList(), start + ContractRotation.WEEK_MILLIS) shouldBe 100_000
        ContractRotation.remainingBudget(1, listOf(state), emptyList(), start + 5_000) shouldBe 0
    }
})
