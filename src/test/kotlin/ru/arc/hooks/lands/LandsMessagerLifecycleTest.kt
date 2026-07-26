package ru.arc.hooks.lands

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.RedisOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LandsMessagerLifecycleTest {
    @Test
    fun `expired requests complete exceptionally and are removed`() {
        val scheduler = TestTaskScheduler()
        Tasks.withScheduler(scheduler) {
            val messager = LandsMessager(mockk<RedisOperations>(relaxed = true), "request", "response")
            messager.init()

            val (requestId, future) = addExpiredRequest(messager)
            scheduler.tick(20)

            assertFalse(requestId in messager.futures)
            assertTrue(future.isCompletedExceptionally)
            messager.close()
        }
    }

    @Test
    fun `close cancels pending requests and cleanup task`() {
        val scheduler = TestTaskScheduler()
        Tasks.withScheduler(scheduler) {
            val messager = LandsMessager(mockk<RedisOperations>(relaxed = true), "request", "response")
            messager.init()
            val pending = CompletableFuture<ru.arc.common.ServerLocation>()
            messager.futures[UUID.randomUUID()] = LandsMessager.TimedRequest(pending, Instant.now())

            messager.close()

            assertTrue(pending.isCancelled)
            assertTrue(messager.futures.isEmpty())

            val (retainedId, _) = addExpiredRequest(messager)
            scheduler.tick(20)
            assertTrue(retainedId in messager.futures)
        }
    }

    @Test
    fun `publish failure completes request exceptionally without retaining it`() {
        val redis = mockk<RedisOperations>(relaxed = true)
        every { redis.publish(any(), any()) } throws IllegalStateException("offline")
        val messager = LandsMessager(redis, "request", "response")

        val future = messager.getSpawnLocation(UUID.randomUUID())

        assertTrue(future.isCompletedExceptionally)
        assertTrue(messager.futures.isEmpty())
    }

    private fun addExpiredRequest(
        messager: LandsMessager,
    ): Pair<UUID, CompletableFuture<ru.arc.common.ServerLocation>> {
        val id = UUID.randomUUID()
        val future = CompletableFuture<ru.arc.common.ServerLocation>()
        messager.futures[id] =
            LandsMessager.TimedRequest(
                future,
                Instant.now().minusSeconds(10),
            )
        return id to future
    }
}
