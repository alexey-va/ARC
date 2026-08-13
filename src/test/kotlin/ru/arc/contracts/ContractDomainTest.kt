package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractDomainTest : StringSpec({
    val definition =
        ResourceContractDefinition(
            id = "road_stone",
            displayName = "Камень для тракта",
            itemKey = "minecraft:stone",
            funding = ContractFunding.SERVER_ENVELOPE,
            windowStartsAt = 1_000L,
            windowEndsAt = 2_000L,
            payoutMinorPerUnit = 250L,
            budgetMinor = 25_000L,
            targetQuantity = 100L,
            perPlayerQuantityCap = 40L,
            minSubmissionQuantity = 4,
            maxSubmissionQuantity = 32,
        )

    "normalizes bounded vanilla item keys" {
        ResourceContractDefinition.normalizeItemKey(" Stone ") shouldBe "minecraft:stone"
        ResourceContractDefinition.normalizeItemKey("slimefun:BASIC_MACHINE") shouldBe "slimefun:basic_machine"
    }

    "rejects blank, non-namespaced and malformed item identities at the domain boundary" {
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "")
        }.message shouldBe "Invalid namespaced contract itemKey: "
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "stone")
        }.message shouldBe "Contract itemKey must be normalized: stone"
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "minecraft:stone?")
        }.message shouldBe "Invalid namespaced contract itemKey: minecraft:stone?"
    }

    "plans and commits only the bounded quantity and exact minor-unit payout" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(
                definition,
                initial,
                submissionId = "submission-1",
                playerId = "player-1",
                requestedQuantity = 64,
                now = 1_500L,
            ) as ContractSubmissionPlan.Accepted

        plan.acceptedQuantity shouldBe 32L
        plan.payoutMinor shouldBe 8_000L
        val committed = ResourceContractEngine.commit(definition, initial, plan, committedAt = 1_501L)
        committed.changed shouldBe true
        committed.state.acceptedQuantity shouldBe 32L
        committed.state.spentMinor shouldBe 8_000L
        committed.state.perPlayerQuantity["player-1"] shouldBe 32L
        committed.state.revision shouldBe 1L
    }

    "enforces the shared player cap across repeated submissions" {
        val initial = ResourceContractState.empty(definition)
        val first =
            ResourceContractEngine.plan(definition, initial, "one", "player-1", 32, 1_500L)
                as ContractSubmissionPlan.Accepted
        val afterFirst = ResourceContractEngine.commit(definition, initial, first, 1_501L).state
        val second =
            ResourceContractEngine.plan(definition, afterFirst, "two", "player-1", 32, 1_502L)
                as ContractSubmissionPlan.Accepted
        second.acceptedQuantity shouldBe 8L
        val completed = ResourceContractEngine.commit(definition, afterFirst, second, 1_503L).state
        completed.perPlayerQuantity["player-1"] shouldBe 40L

        ResourceContractEngine.plan(definition, completed, "three", "player-1", 4, 1_504L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.PLAYER_CAP_REACHED)
    }

    "never spends beyond the configured server envelope" {
        val constrained = definition.copy(budgetMinor = 3_000L, targetQuantity = 100L)
        val plan =
            ResourceContractEngine.plan(
                constrained,
                ResourceContractState.empty(constrained),
                "budgeted",
                "player-1",
                32,
                1_500L,
            ) as ContractSubmissionPlan.Accepted
        plan.acceptedQuantity shouldBe 12L
        plan.payoutMinor shouldBe 3_000L
        ResourceContractEngine.commit(constrained, ResourceContractState.empty(constrained), plan, 1_501L)
            .state.status shouldBe ContractStatus.COMPLETED
    }

    "replays a committed submission id without paying twice" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(definition, initial, "stable-id", "player-1", 8, 1_500L)
                as ContractSubmissionPlan.Accepted
        val first = ResourceContractEngine.commit(definition, initial, plan, 1_501L)

        ResourceContractEngine.plan(definition, first.state, "stable-id", "player-1", 8, 1_502L) shouldBe
            ContractSubmissionPlan.Duplicate(first.receipt)
        val replay = ResourceContractEngine.commit(definition, first.state, plan, 1_503L)
        replay.changed shouldBe false
        replay.state shouldBe first.state
    }

    "round-trips the network repository record without losing exact state" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(definition, initial, "persisted-id", "player-1", 8, 1_500L)
                as ContractSubmissionPlan.Accepted
        val record =
            ResourceContractRecord(
                ResourceContractRecord.stateId(definition.id, definition.windowStartsAt),
                ResourceContractEngine.commit(definition, initial, plan, 1_501L).state,
            )

        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(record), ResourceContractRecord::class.java)

        restored shouldBe record
        restored.id() shouldBe "road_stone:1000"
    }

    "rejects a corrupted Redis record even when Gson bypasses constructors" {
        val gson = Gson()
        val json = gson.toJsonTree(ResourceContractRecord.empty(definition)).asJsonObject
        json.getAsJsonObject("state").addProperty("acceptedQuantity", 1L)
        val corrupted = gson.fromJson(json, ResourceContractRecord::class.java)

        shouldThrow<IllegalArgumentException> {
            corrupted.state.validatedAgainst(definition)
        }.message shouldBe "Player quantities do not equal accepted quantity"
    }

    "rejects stale concurrent plans" {
        val initial = ResourceContractState.empty(definition)
        val first = ResourceContractEngine.plan(definition, initial, "one", "player-1", 8, 1_500L)
            as ContractSubmissionPlan.Accepted
        val stale = ResourceContractEngine.plan(definition, initial, "two", "player-2", 8, 1_500L)
            as ContractSubmissionPlan.Accepted
        val changed = ResourceContractEngine.commit(definition, initial, first, 1_501L).state

        shouldThrow<IllegalArgumentException> {
            ResourceContractEngine.commit(definition, changed, stale, 1_502L)
        }.message shouldBe SubmissionRejection.STALE_STATE.label
    }

    "rejects submissions outside the configured window and minimum" {
        val state = ResourceContractState.empty(definition)
        ResourceContractEngine.plan(definition, state, "early", "player-1", 8, 999L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.CONTRACT_NOT_OPEN)
        ResourceContractEngine.plan(definition, state, "small", "player-1", 3, 1_500L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.BELOW_MINIMUM)
    }

    "rejects impossible or oversized submission policies before runtime" {
        shouldThrow<IllegalArgumentException> {
            definition.copy(maxSubmissionQuantity = 2_305)
        }.message shouldBe "Contract maximum submission must not exceed 2304"
        shouldThrow<IllegalArgumentException> {
            definition.copy(perPlayerQuantityCap = 3L)
        }.message shouldBe "Contract player cap must accept at least one minimum submission"
        shouldThrow<IllegalArgumentException> {
            definition.copy(budgetMinor = 999L)
        }.message shouldBe "Contract budget must fund at least one minimum submission"
    }
})
