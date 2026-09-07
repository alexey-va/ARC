package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class StreamingAuditSummaryTest : FreeSpec({
    "one-pass summary matches the legacy result without retaining all records" {
        val data = AuditData.create("Player")
        data.transactions +=
            Transaction(
                type = Type.JOB,
                amount = 75.0,
                comment = "job",
                timestamp = 2_000,
                timestamp2 = 2_000,
                source = EconomySource.JOBS,
                flow = EconomyFlow.MINT,
                currency = "vault",
                server = "survival",
                origin = "test",
                eventId = "one",
                context = EconomyLedgerContext(action = "job_reward"),
            )
        data.transactions +=
            Transaction(
                type = Type.PAY,
                amount = -25.0,
                comment = "pay",
                timestamp = 3_000,
                timestamp2 = 3_000,
                source = EconomySource.PLAYER_TRANSFER,
                flow = EconomyFlow.TRANSFER,
                currency = "vault",
                server = "survival",
                origin = "test",
                eventId = "two",
            )
        val arguments =
            SummaryArguments(
                generatedAt = 4_000,
                since = 1_000,
                limit = 10,
            )
        val legacy =
            buildAuditSummary(
                data = listOf(data),
                generatedAt = arguments.generatedAt,
                since = arguments.since,
                limit = arguments.limit,
                serverFilter = null,
                anomalies = emptyList(),
            )
        val streaming =
            StreamingAuditSummary(
                generatedAt = arguments.generatedAt,
                since = arguments.since,
                limit = arguments.limit,
                serverFilter = null,
                rapidWindowMillis = 300_000,
                rapidAmount = 250_000.0,
                rapidTransactions = 40,
                largeTransactionAmount = 100_000.0,
                slimefunBuyOnlyPolicyEnabled = false,
                slimefunBuyOnlyPolicyActivatedAt = 0,
                shopMaterials = emptySet(),
                concentrationGroups = emptyMap(),
            )
        data.transactions.forEach { streaming.accept(AuditEvent(data.name, it)) }

        streaming.finish(emptyList()) shouldBe legacy
    }

    "keeps mint burn and transfer totals separate per currency" {
        val data = AuditData.create("Player")
        data.transactions += Transaction(Type.JOB, 100_000.0, "vault mint", 1_000, 1_000, EconomySource.JOBS, EconomyFlow.MINT, "vault", eventId = "vault")
        data.transactions += Transaction(Type.PAY, -25.0, "token burn", 2_000, 2_000, EconomySource.MOUNTS, EconomyFlow.BURN, "tokens", eventId = "tokens")
        data.transactions += Transaction(Type.PAY, 500.0, "bank transfer", 3_000, 3_000, EconomySource.PLAYER_TRANSFER, EconomyFlow.TRANSFER, "vault", eventId = "transfer")
        data.transactions += Transaction(
            Type.PAY,
            -900.0,
            "failed token attempt",
            3_500,
            3_500,
            EconomySource.MOUNTS,
            EconomyFlow.BURN,
            "tokens",
            eventId = "failed-attempt",
            context = EconomyLedgerContext(recordKind = EconomyRecordKind.ATTEMPT, status = EconomyEventStatus.FAILED),
        )
        val summary = buildAuditSummary(listOf(data), 4_000, 0, 10, null, emptyList())
        val streaming = StreamingAuditSummary(
            generatedAt = 4_000,
            since = 0,
            limit = 10,
            serverFilter = null,
            rapidWindowMillis = 300_000,
            rapidAmount = 250_000.0,
            rapidTransactions = 40,
            largeTransactionAmount = 100_000.0,
            slimefunBuyOnlyPolicyEnabled = false,
            slimefunBuyOnlyPolicyActivatedAt = 0,
            shopMaterials = emptySet(),
            concentrationGroups = emptyMap(),
        )
        data.transactions.forEach { streaming.accept(AuditEvent(data.name, it)) }
        streaming.finish(emptyList()) shouldBe summary
        val currencies = summary["currencies"] as List<*>
        val vault = currencies.single { (it as Map<*, *>)["currency"] == "vault" } as Map<*, *>
        val tokens = currencies.single { (it as Map<*, *>)["currency"] == "tokens" } as Map<*, *>
        vault["minted"] shouldBe 100_000.0
        vault["mintBurnNet"] shouldBe 100_000.0
        vault["transferIn"] shouldBe 500.0
        tokens["minted"] shouldBe 0.0
        tokens["burned"] shouldBe 25.0
        val vaultSources = vault["sources"] as List<*>
        val jobs = vaultSources.single { (it as Map<*, *>)["source"] == "jobs" } as Map<*, *>
        jobs["income"] shouldBe 100_000.0
        val mounts = (tokens["sources"] as List<*>).single { (it as Map<*, *>)["source"] == "mounts" } as Map<*, *>
        mounts["expense"] shouldBe 25.0
        mounts["records"] shouldBe 1L
        (summary["totals"] as Map<*, *>)["legacyMixedCurrencyAggregation"] shouldBe true
    }
})

private data class SummaryArguments(
    val generatedAt: Long,
    val since: Long,
    val limit: Int,
)
