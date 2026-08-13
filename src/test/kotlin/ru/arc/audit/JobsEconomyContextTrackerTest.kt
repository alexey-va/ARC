package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class JobsEconomyContextTrackerTest : FreeSpec({
    beforeEach {
        JobsEconomyContextTracker.clear()
    }

    "scales a buffered action mix to the exact provider payout" {
        val playerId = UUID.randomUUID()
        JobsEconomyContextTracker.capture(playerId, "Miner", "BREAK", "minecraft:stone", "not_applicable", 25.0, 1_000)
        JobsEconomyContextTracker.capture(playerId, "Miner", "BREAK", "minecraft:stone", "not_applicable", 15.0, 1_001)
        JobsEconomyContextTracker.capture(playerId, "Hunter", "KILL", "minecraft:zombie", "natural", 60.0, 1_002)

        val context = JobsEconomyContextTracker.finalizePayment(playerId, 80.0, 1_100)!!
        val components = context.normalizedJobBreakdown

        context.action shouldBe "job_reward"
        context.requestedAmount!! shouldBeExactly 80.0
        components shouldHaveSize 2
        components.sumOf { it.amount!! } shouldBeExactly 80.0
        components[0].job shouldBe "miner"
        components[0].amount!! shouldBeExactly 32.0
        components[0].normalizedOccurrences shouldBe 2
        components[1].origin shouldBe "natural"
        components[1].amount!! shouldBeExactly 48.0
    }

    "drops stale action evidence before a later payout" {
        val playerId = UUID.randomUUID()
        JobsEconomyContextTracker.capture(playerId, "Miner", "BREAK", "minecraft:stone", "not_applicable", 100.0, 0)
        JobsEconomyContextTracker.capture(playerId, "Builder", "PLACE", "minecraft:oak_planks", "not_applicable", 10.0, 120_001)

        val components = JobsEconomyContextTracker.finalizePayment(playerId, 10.0, 120_010)!!.normalizedJobBreakdown

        components shouldHaveSize 1
        components.single().job shouldBe "builder"
        components.single().amount!! shouldBeExactly 10.0
    }

    "does not attach a stale bucket to a delayed payout" {
        val playerId = UUID.randomUUID()
        JobsEconomyContextTracker.capture(playerId, "Miner", "BREAK", "minecraft:stone", "not_applicable", 10.0, 0)

        JobsEconomyContextTracker.finalizePayment(playerId, 10.0, 120_001)!!.normalizedJobBreakdown shouldBe emptyList()
    }

    "bounds high-cardinality targets with an overflow component" {
        val playerId = UUID.randomUUID()
        repeat(80) { index ->
            JobsEconomyContextTracker.capture(
                playerId,
                "Miner",
                "BREAK",
                "custom:target_$index",
                "not_applicable",
                1.0,
                index.toLong(),
            )
        }

        val components = JobsEconomyContextTracker.finalizePayment(playerId, 80.0, 100)!!.normalizedJobBreakdown

        components shouldHaveSize 64
        components.sumOf { it.amount!! } shouldBeExactly 80.0
        components.last().job shouldBe "other"
        components.last().normalizedOccurrences shouldBe 17
    }

    "discard removes cancelled buffered evidence" {
        val playerId = UUID.randomUUID()
        JobsEconomyContextTracker.capture(playerId, "Hunter", "KILL", "minecraft:zombie", "natural", 10.0, 1_000)

        JobsEconomyContextTracker.discard(playerId)

        JobsEconomyContextTracker.finalizePayment(playerId, 10.0, 1_100)!!.normalizedJobBreakdown shouldBe emptyList()
    }
})
