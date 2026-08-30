package ru.arc.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class AuditSqlCodecTest : StringSpec({
    "enriched event survives SQL row encoding without losing ledger evidence" {
        val context =
            EconomyLedgerContext(
                recordKind = EconomyRecordKind.TRANSACTION,
                status = EconomyEventStatus.SUCCEEDED,
                accountId = "account-42",
                providerTimestamp = 1_786_700_000_100L,
                correlationId = "correlation-7",
                world = "world",
                balanceBefore = 100.0,
                balanceAfter = 104.25,
                balanceEvidence = BalanceEvidence.EXACT_BEFORE_AFTER,
                requestedAmount = 4.25,
                action = "job_reward",
                jobBreakdown =
                    listOf(
                        EconomyJobRewardComponent(
                            job = "miner",
                            activity = "break",
                            target = "stone",
                            origin = "natural",
                            amount = 4.25,
                            occurrences = 1,
                        ),
                    ),
                capturedAt = 1_786_700_000_110L,
            )
        val event =
            AuditEvent(
                "Player",
                Transaction(
                    type = Type.JOB,
                    amount = 4.25,
                    comment = "Deposit",
                    timestamp = 1_786_700_000_000L,
                    timestamp2 = 1_786_700_000_100L,
                    source = EconomySource.JOBS,
                    flow = EconomyFlow.MINT,
                    currency = "vault",
                    server = "survival",
                    origin = "ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration",
                    occurrences = 1,
                    eventId = "event-42",
                    context = context,
                ),
            )

        val row = AuditSqlCodec.encode(event)
        val decoded = AuditSqlCodec.decode(row)

        row.eventId shouldBe "event-42"
        row.playerKey shouldBe "player"
        row.amount shouldBe BigDecimal("4.250000")
        row.source shouldBe "jobs"
        row.flow shouldBe "mint"
        row.server shouldBe "survival"
        decoded.playerName shouldBe "Player"
        decoded.transaction.copy(context = null) shouldBe event.transaction.copy(context = null)
        decoded.transaction.context shouldBe context
    }

    "legacy nullable labels decode to their bounded fallback values" {
        val row =
            AuditSqlRecord(
                eventId = "legacy-event",
                playerName = "LegacyPlayer",
                playerKey = "legacyplayer",
                type = "OTHER",
                amount = BigDecimal("-2.000000"),
                comment = "Old debit",
                timestamp = 100L,
                timestamp2 = 101L,
                source = "legacy",
                flow = "unknown",
                currency = "vault",
                server = "unknown",
                origin = "",
                occurrences = 1,
                contextJson = null,
            )

        val event = AuditSqlCodec.decode(row)

        event.transaction.normalizedSource shouldBe EconomySource.LEGACY
        event.transaction.normalizedFlow shouldBe EconomyFlow.UNKNOWN
        event.transaction.context shouldBe null
        event.transaction.amount shouldBe -2.0
    }

    "migration reclassifies known origins but preserves explicit historical sources" {
        val jobs =
            AuditEvent(
                "Worker",
                Transaction(
                    type = Type.OTHER,
                    amount = 4.25,
                    comment = "Deposit",
                    source = EconomySource.UNKNOWN,
                    origin = "ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration",
                    eventId = "legacy-jobs",
                ),
            )
        val explicit =
            AuditEvent(
                "Worker",
                jobs.transaction.copy(source = EconomySource.CUSTOM_FISHING, eventId = "explicit-source"),
            )

        AuditSqlCodec.encode(jobs).source shouldBe "jobs"
        AuditSqlCodec.encode(explicit).source shouldBe "custom_fishing"
    }

    "codec rejects non finite money before JDBC" {
        val event = AuditEvent("Player", Transaction(Type.OTHER, Double.NaN, "bad", eventId = "event-bad"))

        val failure = runCatching { AuditSqlCodec.encode(event) }.exceptionOrNull()

        failure?.javaClass shouldBe IllegalArgumentException::class.java
    }
})
