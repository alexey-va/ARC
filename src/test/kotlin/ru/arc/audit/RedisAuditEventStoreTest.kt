package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class RedisAuditEventStoreTest : FreeSpec({
    fun event(id: String, at: Long, type: Type = Type.JOB): AuditEvent =
        AuditEvent(
            "Player",
            Transaction(
                type = type,
                amount = 2.5,
                comment = "test",
                timestamp = at,
                timestamp2 = at,
                source = EconomySource.JOBS,
                flow = EconomyFlow.MINT,
                currency = "vault",
                server = "survival",
                origin = "test",
                eventId = id,
            ),
        )

    "append preserves stable identity and ignores a duplicate replay" {
        RedisAuditEventStore(InMemoryAuditRepository()).use { store ->
            store.append(event("same-event", 1)).join().inserted shouldBe true
            store.append(event("same-event", 1)).join().inserted shouldBe false
            store.count().join() shouldBe 1L
        }
    }

    "page and scan expose deterministic order" {
        RedisAuditEventStore(InMemoryAuditRepository()).use { store ->
            store.append(event("one", 1)).join()
            store.append(event("two", 2, Type.PAY)).join()
            store.append(event("three", 3)).join()
            store.page(AuditPageRequest("Player", 1, 10, AuditFilter.JOB)).join()
                .records.map(Transaction::eventId) shouldContainExactly listOf("three", "one")
            val scanned = mutableListOf<String>()
            store.scan(AuditScanRequest(0, 10)) { scanned += it.eventId }.join()
            scanned shouldContainExactly listOf("one", "two", "three")
        }
    }

    "dual write keeps one event id in both stores" {
        val left = RecordingEventStore()
        val right = RecordingEventStore()
        DualWriteAuditEventStore(left, right).use { store ->
            store.append(event("dual-id", 1)).join().inserted shouldBe true
        }
        left.events.single().eventId shouldBe "dual-id"
        right.events.single().eventId shouldBe "dual-id"
    }
})

private class RecordingEventStore : AuditEventStore {
    val events = mutableListOf<AuditEvent>()

    override fun append(event: AuditEvent) =
        CompletableFuture.completedFuture(AuditAppendResult(events.none { it.eventId == event.eventId }))
            .also { if (events.none { it.eventId == event.eventId }) events += event }

    override fun page(request: AuditPageRequest) = CompletableFuture.completedFuture(AuditPage(emptyList(), 0))
    override fun scan(request: AuditScanRequest, consumer: AuditEventConsumer) = CompletableFuture.completedFuture(0L)
    override fun count() = CompletableFuture.completedFuture(events.size.toLong())
    override fun clearPlayer(playerName: String) = CompletableFuture.completedFuture(0)
    override fun clearAll() = CompletableFuture.completedFuture(0)
    override fun prune(beforeEpochMs: Long) = CompletableFuture.completedFuture(0)
    override fun status() = CompletableFuture.completedFuture(AuditStorageStatus(AuditStorageMode.SQL, true))
    override fun close() = Unit
}
