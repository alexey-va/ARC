package ru.arc.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class JobsAuditCoalescerTest : StringSpec({
    "coalesces one player's minute of Jobs payouts without losing totals" {
        val written = mutableListOf<AuditEvent>()
        val coalescer = JobsAuditCoalescer(60_000, 100, 100) { event ->
            written += event
            CompletableFuture.completedFuture(AuditAppendResult(true))
        }
        val first = coalescer.append(jobEvent("event-1", 4.25, 1_000, "builder", 12), acceptedAt = 0)
        val second = coalescer.append(jobEvent("event-2", 2.75, 2_000, "builder", 8), acceptedAt = 1_000)

        first.isDone shouldBe false
        coalescer.flushDue(59_999) shouldBe 0
        coalescer.flushDue(60_000) shouldBe 1

        written.size shouldBe 1
        written.single().transaction.amount shouldBe 7.0
        written.single().transaction.occurrenceCount shouldBe 2
        written.single().transaction.context!!.normalizedJobBreakdown.single().amount shouldBe 7.0
        written.single().transaction.context!!.normalizedJobBreakdown.single().normalizedOccurrences shouldBe 20
        first.join().inserted shouldBe true
        second.join().inserted shouldBe true
        coalescer.pendingCount shouldBe 0
    }

    "does not coalesce ordinary economy operations" {
        val written = mutableListOf<AuditEvent>()
        val coalescer = JobsAuditCoalescer(60_000, 100, 100) { event ->
            written += event
            CompletableFuture.completedFuture(AuditAppendResult(true))
        }

        coalescer.append(jobEvent("shop", 10.0, 1_000, "builder", 1).let { event ->
            event.copy(transaction = event.transaction.copy(source = EconomySource.SHOP, context = null))
        }).join()

        written.size shouldBe 1
        coalescer.pendingCount shouldBe 0
    }
})

private fun jobEvent(eventId: String, amount: Double, at: Long, job: String, actions: Int): AuditEvent =
    AuditEvent(
        "Builder",
        Transaction(
            type = Type.JOB,
            amount = amount,
            comment = "Jobs payout",
            timestamp = at,
            timestamp2 = at,
            source = EconomySource.JOBS,
            flow = EconomyFlow.MINT,
            currency = "vault",
            server = "survival",
            origin = "ru.ruscrafting.ecojobs.integration.VaultEconomyIntegration",
            eventId = eventId,
            context =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    requestedAmount = amount,
                    action = EconomyAction.JOB_REWARD.label,
                    jobBreakdown =
                        listOf(
                            EconomyJobRewardComponent(
                                job = job,
                                activity = "place",
                                target = "stone",
                                origin = "natural",
                                amount = amount,
                                occurrences = actions,
                            ),
                        ),
                    capturedAt = at,
                ),
        ),
    )
