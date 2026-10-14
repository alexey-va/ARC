package ru.arc.ops

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import ru.arc.contracts.api.ArcContractsService
import ru.arc.contracts.api.ArcContractsService.SubmissionReconciliationRecord
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyReconciliationRecord
import ru.arc.paper.testing.MockBukkitTestRuntime

class ExtractedContractWireTest : StringSpec({
    "resource reconciliation keeps nested evidence and exact minor units across the service boundary" {
        MockBukkitTestRuntime.open().use { runtime ->
            val service = mockk<ArcContractsService>()
            val record = SubmissionReconciliationRecord(
                "submission", "road_stone", 10L, "minecraft:stone", "player", 64L, 9_007_199_254_740_993L,
                "payout", "committed", 4L, 20L, "payment_started", "provider_unknown", "evidence",
                100L, 200L, "tx", "payment_confirmed", "provider_transaction", "operator", "checked",
                "idempotency", "digest", 3L, "payment_started", "provider_unknown", "original", 30L,
            )
            every { service.submissionReconciliation("submission") } returns record
            runtime.server.servicesManager.register(ArcContractsService::class.java, service,
                runtime.createSimplePlugin("ArcContracts"), ServicePriority.Normal)
            val response = OpsContractReconciliationHandlers.get("submission")
            val wire = response["record"] as Map<*, *>
            wire["payoutMinor"] shouldBe 9_007_199_254_740_993L
            wire["status"] shouldBe "committed"
            wire["reconciliation"] shouldBe linkedMapOf(
                "resolution" to "payment_confirmed", "evidenceKind" to "provider_transaction",
                "operatorId" to "operator", "operatorEvidence" to "checked", "idempotencyKey" to "idempotency",
                "reviewDigest" to "digest", "reviewedRevision" to 3L, "reviewFromStatus" to "payment_started",
                "reviewReason" to "provider_unknown", "originalReviewEvidence" to "original", "reconciledAt" to 30L,
            )
        }
    }

    "season money reconciliation preserves separate original and reconciliation provider evidence" {
        MockBukkitTestRuntime.open().use { runtime ->
            val service = mockk<ArcContractsService>()
            val record = SeasonMoneyReconciliationRecord(
                "action", "season", "project_cash", "project", "player", 500L, "withdrawal", "committed",
                4L, 20L, "withdrawal_started", "provider_unknown", "evidence", 1000L, 500L, "original-tx",
                "withdrawal_confirmed", "provider_transaction", "operator", "checked", "idempotency", "digest",
                3L, "withdrawal_started", "provider_unknown", "original", 25L, 500L, "review-tx", 30L,
            )
            every { service.seasonMoneyReconciliation("action") } returns record
            runtime.server.servicesManager.register(ArcContractsService::class.java, service,
                runtime.createSimplePlugin("ArcContracts"), ServicePriority.Normal)
            val wire = OpsSeasonMoneyReconciliationHandlers.get("action")["record"] as Map<*, *>
            wire["providerTransactionId"] shouldBe "original-tx"
            wire["kind"] shouldBe "project_cash"
            val reconciliation = wire["reconciliation"] as Map<*, *>
            reconciliation["providerTransactionId"] shouldBe "review-tx"
            reconciliation["providerHistoryCheckedAt"] shouldBe 25L
            reconciliation["providerBalanceAfterMinor"] shouldBe 500L
            reconciliation["reviewedRevision"] shouldBe 3L
        }
    }
})
