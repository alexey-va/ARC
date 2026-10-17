package ru.arc.eliteloot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BoundedAsyncExecutor
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LostLootOutboxTest : FreeSpec({
    fun capture(id: String = UUID.randomUUID().toString(), bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        LostLootCapture(id, UUID.randomUUID().toString(), bytes, 1234, 12.5)

    "cold journal and blocked write never execute on admission and accepted captures drain in order" {
        val caller = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rows = MemoryLostLootJournal()
        val outbox = LostLootOutbox({
            (Thread.currentThread() === caller) shouldBe false
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); rows
        }, { CompletableFuture.completedFuture(Unit) })
        try {
            val ready = outbox.start()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val first = capture()
            val second = capture()
            val accepted1 = outbox.capture(first)
            val accepted2 = outbox.capture(second)
            val drain = outbox.closeAsync()
            ready.isDone shouldBe false
            accepted1.isDone shouldBe false
            accepted2.isDone shouldBe false
            drain.isDone shouldBe false
            release.countDown()
            accepted1.get(5, TimeUnit.SECONDS).item shouldBe Base64.getEncoder().encodeToString(first.bytes)
            accepted2.get(5, TimeUnit.SECONDS).id shouldBe second.id
            drain.get(5, TimeUnit.SECONDS)
            rows.order shouldBe listOf(first.id, second.id)
        } finally { release.countDown(); outbox.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "uncertain write retains the first payload and becomes certain only after verified retry" {
        val rows = object : MemoryLostLootJournal() {
            var fail = true
            override fun write(record: LostLootRecord): LostLootRecord {
                values[record.id] = record
                if (fail) { fail = false; error("unknown fsync/readback outcome") }
                return super.write(record)
            }
        }
        val outbox = LostLootOutbox({ rows }, { CompletableFuture.completedFuture(Unit) })
        try {
            outbox.start().get(5, TimeUnit.SECONDS)
            val first = capture()
            shouldThrow<Exception> { outbox.capture(first).get(5, TimeUnit.SECONDS) }
            outbox.entry(first.id)?.certain shouldBe false
            val retry = outbox.capture(first.copy(bytes = byteArrayOf(9))).get(5, TimeUnit.SECONDS)
            retry.item shouldBe Base64.getEncoder().encodeToString(first.bytes)
            outbox.entry(first.id)?.certain shouldBe true
        } finally { outbox.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "SQL outage leaves the durable outbox available and terminal acknowledgement precedes resource close" {
        val rows = MemoryLostLootJournal()
        val sql = CompletableFuture<Unit>()
        val published = CountDownLatch(1)
        val outbox = LostLootOutbox({ rows }, { published.countDown(); sql })
        val caller = Thread.currentThread()
        var finalizerRan = false
        try {
            outbox.start().get(5, TimeUnit.SECONDS)
            val record = outbox.capture(capture()).get(5, TimeUnit.SECONDS)
            outbox.publishPending().get(5, TimeUnit.SECONDS)
            published.await(5, TimeUnit.SECONDS) shouldBe true
            outbox.entry(record.id)?.certain shouldBe true
            val cancelledObserver = outbox.closeAsync {
                (Thread.currentThread() === caller) shouldBe false
                rows.get(record.id)?.state shouldBe LostLootState.EXPORTED
                finalizerRan = true
            }
            cancelledObserver.cancel(false)
            val actualObserver = outbox.closeAsync()
            actualObserver.isDone shouldBe false
            sql.complete(Unit)
            actualObserver.get(5, TimeUnit.SECONDS)
            finalizerRan shouldBe true
            rows.get(record.id)?.item shouldBe null
        } finally { sql.complete(Unit); outbox.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "full admission rejects explicitly and the reserved terminal still drains accepted capture" {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rows = object : MemoryLostLootJournal() {
            override fun write(record: LostLootRecord): LostLootRecord {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return super.write(record)
            }
        }
        val outbox = LostLootOutbox({ rows }, { CompletableFuture.completedFuture(Unit) }, BoundedAsyncExecutor("lost-loot-capacity-test", capacity = 1))
        try {
            outbox.start().get(5, TimeUnit.SECONDS)
            val first = outbox.capture(capture())
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val second = outbox.capture(capture())
            val rejected = outbox.capture(capture())
            rejected.isCompletedExceptionally shouldBe true
            val drain = outbox.closeAsync()
            drain.isDone shouldBe false
            release.countDown()
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS); drain.get(5, TimeUnit.SECONDS)
            rows.values.size shouldBe 2
        } finally { release.countDown(); outbox.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "failed initialization is unavailable and cannot report a successful capture" {
        val outbox = LostLootOutbox({ error("cold journal corruption") }, { CompletableFuture.completedFuture(Unit) })
        try {
            shouldThrow<Exception> { outbox.start().get(5, TimeUnit.SECONDS) }
            outbox.state shouldBe LostLootOutbox.State.FAILED
            shouldThrow<Exception> { outbox.capture(capture()).get(5, TimeUnit.SECONDS) }
        } finally { outbox.closeAsync().get(5, TimeUnit.SECONDS) }
    }
})

internal open class MemoryLostLootJournal : LostLootJournal {
    val values = ConcurrentHashMap<String, LostLootRecord>()
    val verified = ConcurrentHashMap.newKeySet<String>()
    val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun records(): Collection<LostLootRecord> = values.values
    override fun pending(limit: Int): List<LostLootRecord> = values.values.filter { it.id in verified && it.state == LostLootState.STORED }.take(limit)
    override fun get(id: String) = values[id]
    override fun certain(id: String) = id in verified
    override fun write(record: LostLootRecord): LostLootRecord {
        values[record.id] = record
        verified.add(record.id)
        order.add(record.id)
        return record
    }
}
