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
})
