package ru.arc.sync.base

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ARC
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.util.Common
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

class SyncRepoTest : FreeSpec({
    "save future completes only after Redis persistence succeeds" {
        val redis = InMemoryRedis()
        val id = UUID.randomUUID()
        val repo = testRepo(redis, TestSyncData(id))

        repo.saveAndPersistData(Context()).join()

        redis.getHash("test:sync").containsKey(id.toString()) shouldBe true
    }

    "save future propagates Redis persistence failure" {
        val redis = InMemoryRedis().apply { failOnSave = true }
        val repo = testRepo(redis, TestSyncData(UUID.randomUUID()))

        shouldThrow<CompletionException> {
            repo.saveAndPersistData(Context()).join()
        }
    }

    "save future propagates data producer failure" {
        val repo =
            SyncRepo(
                clazz = TestSyncData::class.java,
                key = "test:sync",
                redisManager = InMemoryRedis(),
                dataApplier = {},
                dataProducer = { error("producer failed") },
            )

        val failure =
            shouldThrow<CompletionException> {
                repo.saveAndPersistData(Context()).join()
            }

        failure.cause?.message shouldBe "producer failed"
    }

    "sync load future completes only after data is applied on the main scheduler" {
        val redis = InMemoryRedis()
        val id = UUID.randomUUID()
        val data = TestSyncData(id, sourceServer = "survival")
        redis.saveMapEntries("test:sync", id.toString(), Common.gson.toJson(data)).join()
        var applied = false
        val repo =
            SyncRepo(
                clazz = TestSyncData::class.java,
                key = "test:sync",
                redisManager = redis,
                dataApplier = { applied = true },
                dataProducer = { data },
            )
        val scheduler = TestTaskScheduler()
        ARC.serverName = "spawn"

        Tasks.withScheduler(scheduler) {
            val future = repo.loadAndApplyData(id)

            future.isDone shouldBe false
            applied shouldBe false

            executeUntilDone(future, scheduler)
            applied shouldBe true
        }
    }

    "load ignores data written by the current server" {
        val redis = InMemoryRedis()
        val id = UUID.randomUUID()
        val data = TestSyncData(id, sourceServer = "spawn")
        redis.saveMapEntries("test:sync", id.toString(), Common.gson.toJson(data)).join()
        var applied = false
        val repo =
            SyncRepo(
                clazz = TestSyncData::class.java,
                key = "test:sync",
                redisManager = redis,
                dataApplier = { applied = true },
                dataProducer = { data },
            )
        val scheduler = TestTaskScheduler()
        ARC.serverName = "spawn"

        Tasks.withScheduler(scheduler) {
            val future = repo.loadAndApplyData(id)
            executeUntilDone(future, scheduler)
        }

        applied shouldBe false
    }

    "repository rejects a blank Redis key" {
        shouldThrow<IllegalArgumentException> {
            SyncRepo(
                clazz = TestSyncData::class.java,
                key = " ",
                redisManager = InMemoryRedis(),
                dataApplier = {},
                dataProducer = { null },
            )
        }
    }
})

private fun executeUntilDone(
    future: CompletableFuture<*>,
    scheduler: TestTaskScheduler,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!future.isDone && System.nanoTime() < deadline) {
        scheduler.executeImmediate()
        Thread.onSpinWait()
    }
    future.isDone shouldBe true
    future.join()
}

private fun testRepo(
    redis: InMemoryRedis,
    data: TestSyncData,
): SyncRepo<TestSyncData> =
    SyncRepo(
        clazz = TestSyncData::class.java,
        key = "test:sync",
        redisManager = redis,
        dataApplier = {},
        dataProducer = { data },
    )

private data class TestSyncData(
    val id: UUID,
    val timestamp: Long = 1L,
    val sourceServer: String = "spawn",
) : SyncData {
    override fun timestamp(): Long = timestamp
    override fun server(): String = sourceServer
    override fun uuid(): UUID = id
}
