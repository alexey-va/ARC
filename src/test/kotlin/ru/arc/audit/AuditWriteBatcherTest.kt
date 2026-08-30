package ru.arc.audit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class AuditWriteBatcherTest : StringSpec({
    fun event(id: String) = AuditEvent("Player", Transaction(Type.JOB, 1.0, "Deposit", eventId = id))

    "producer enqueue does not invoke JDBC sink before an asynchronous flush" {
        val batches = mutableListOf<List<String>>()
        val batcher = AuditWriteBatcher(maximumPendingEvents = 10, batchSize = 5) { events ->
            batches += events.map(AuditEvent::eventId)
            CompletableFuture.completedFuture(events.map { AuditAppendResult(true) })
        }

        val completion = batcher.append(event("one"))

        completion.isDone.shouldBeFalse()
        batches shouldBe emptyList()
        batcher.pendingCount shouldBe 1

        batcher.flush().join()

        completion.join() shouldBe AuditAppendResult(true)
        batches.single() shouldContainExactly listOf("one")
        batcher.pendingCount shouldBe 0
    }

    "one flush drains no more than configured batch size" {
        val batches = mutableListOf<List<String>>()
        val batcher = AuditWriteBatcher(maximumPendingEvents = 10, batchSize = 2) { events ->
            batches += events.map(AuditEvent::eventId)
            CompletableFuture.completedFuture(events.map { AuditAppendResult(true) })
        }
        listOf("one", "two", "three").forEach { batcher.append(event(it)) }

        batcher.flush().join()

        batches.single() shouldContainExactly listOf("one", "two")
        batcher.pendingCount shouldBe 1
    }

    "failed batch is retried unchanged and caller futures complete only after success" {
        val batches = mutableListOf<List<String>>()
        var fail = true
        val batcher = AuditWriteBatcher(maximumPendingEvents = 10, batchSize = 10) { events ->
            batches += events.map(AuditEvent::eventId)
            if (fail) {
                fail = false
                CompletableFuture.failedFuture(IllegalStateException("database unavailable"))
            } else {
                CompletableFuture.completedFuture(events.map { AuditAppendResult(true) })
            }
        }
        val first = batcher.append(event("one"))
        val second = batcher.append(event("two"))

        runCatching { batcher.flush().join() }

        first.isDone.shouldBeFalse()
        second.isDone.shouldBeFalse()
        batcher.pendingCount shouldBe 2

        batcher.flush().join()

        first.isDone.shouldBeTrue()
        second.isDone.shouldBeTrue()
        batches shouldContainExactly listOf(listOf("one", "two"), listOf("one", "two"))
    }

    "bounded queue rejects overflow instead of growing heap" {
        val batcher = AuditWriteBatcher(maximumPendingEvents = 2, batchSize = 2) { events ->
            CompletableFuture.completedFuture(events.map { AuditAppendResult(true) })
        }
        batcher.append(event("one"))
        batcher.append(event("two"))

        val overflow = batcher.append(event("three"))

        overflow.isCompletedExceptionally.shouldBeTrue()
        batcher.pendingCount shouldBe 2
    }
})
