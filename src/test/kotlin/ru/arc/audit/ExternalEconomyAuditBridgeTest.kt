package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ExternalEconomyAuditBridgeTest : FreeSpec({
    beforeEach { EconomyPendingContextTracker.clear() }
    afterEach { EconomyPendingContextTracker.clear() }

    "ArcEcoJobs marker supplies bounded job evidence to the next exact payout" {
        val playerId = UUID.randomUUID()
        ExternalEconomyAuditBridge.markJobReward(playerId, "Builder", 4.25)

        val context = EconomyPendingContextTracker.consume(playerId, 4.25, System.currentTimeMillis(), EconomySource.JOBS)!!
        context.action shouldBe "job_reward"
        context.normalizedJobBreakdown.single().job shouldBe "builder"
        context.normalizedJobBreakdown.single().activity shouldBe "payout"
        context.normalizedJobBreakdown.single().amount shouldBe 4.25
    }

    "failed payout can cancel its marker before another equal deposit" {
        val playerId = UUID.randomUUID()
        val token = ExternalEconomyAuditBridge.markJobReward(playerId, "Miner", 2.0)
        ExternalEconomyAuditBridge.cancel(playerId, token)

        EconomyPendingContextTracker.consume(playerId, 2.0, System.currentTimeMillis(), EconomySource.JOBS) shouldBe null
    }

    "external reward bridge keeps source, currency and reward identity" {
        val playerId = UUID.randomUUID()
        ExternalEconomyAuditBridge.markExternalReward(playerId, "arcvotes", "vote_reward", 3.0, "tokens", "vote-1")

        val matched = EconomyPendingContextTracker.consumeMatch(
            playerId,
            3.0,
            System.currentTimeMillis(),
            EconomySource.VOTING,
            "tokens",
        )!!
        matched.source shouldBe EconomySource.VOTING
        matched.context.action shouldBe "vote_reward"
        matched.context.correlationId shouldBe "vote-1"
    }

    "currency mismatch does not consume an external marker" {
        val playerId = UUID.randomUUID()
        ExternalEconomyAuditBridge.markExternalReward(playerId, "arcranks", "contract_reward", 3.0, "tokens", "rank-1")

        EconomyPendingContextTracker.consumeMatch(
            playerId,
            3.0,
            System.currentTimeMillis(),
            EconomySource.RANKS,
            "vault",
        ) shouldBe null
    }

    "farm reward marker carries the durable grant id only to the matching currency and source" {
        val playerId = UUID.randomUUID()
        ExternalEconomyAuditBridge.markExternalReward(playerId, "arcfarms", "farm_reward", 500.0, "vault", "farm:42:grant")
        val now = System.currentTimeMillis()
        EconomyPendingContextTracker.consumeMatch(playerId, 500.0, now, EconomySource.FARMS, "tokens") shouldBe null
        EconomyPendingContextTracker.consumeMatch(playerId, 500.0, now, EconomySource.JOBS, "vault") shouldBe null
        val matched = EconomyPendingContextTracker.consumeMatch(playerId, 500.0, now, EconomySource.FARMS, "vault")!!
        matched.context.action shouldBe "farm_reward"
        matched.context.correlationId shouldBe "farm:42:grant"
        EconomyPendingContextTracker.consumeMatch(playerId, 500.0, now, EconomySource.FARMS, "vault") shouldBe null
    }

    "unknown external source is rejected instead of guessed" {
        val playerId = UUID.randomUUID()
        ExternalEconomyAuditBridge.markExternalReward(playerId, "reflection", "credit", 3.0, "tokens", "x") shouldBe null
        EconomyPendingContextTracker.consumeMatch(playerId, 3.0, System.currentTimeMillis(), null, "tokens") shouldBe null
    }

    "external bridge rejects actions outside the source allowlist" {
        ExternalEconomyAuditBridge.markExternalReward(
            UUID.randomUUID(),
            "voting",
            "arbitrary_credit",
            3.0,
            "tokens",
            "x",
        ) shouldBe null
    }
})
