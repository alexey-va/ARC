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
})

private data class SummaryArguments(
    val generatedAt: Long,
    val since: Long,
    val limit: Int,
)
