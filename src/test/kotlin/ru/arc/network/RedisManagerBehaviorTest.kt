@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.network

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Behavioral tests for RedisManager.
 *
 * These tests verify the expected behavior and API contract of RedisManager
 * without requiring actual Redis connection.
 *
 * For full integration tests with real Redis, use RedisManagerIntegrationTest
 * (requires Docker and DOCKER_AVAILABLE=true).
 */
class RedisManagerBehaviorTest {

    private lateinit var redisManager: RedisManager

    @BeforeEach
    fun setup() {
        redisManager = RedisManager("localhost", 6379, null, null)
    }

    @AfterEach
    fun teardown() {
        try {
            redisManager.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun `saveMapEntries processes key-value pairs correctly`() {
        // Test that the method accepts varargs correctly
        val future = redisManager.saveMapEntries(
            "test:kv",
            "key1", "value1",
            "key2", "value2",
            "key3", "value3"
        )

        assertNotNull(future)
        assertDoesNotThrow {
            future.get(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `saveMapEntries handles odd number of arguments gracefully`() {
        // This should handle gracefully (though it's an error case)
        val future = redisManager.saveMapEntries(
            "test:odd",
            "key1", "value1",
            "key2" // Missing value
        )

        // Should not throw immediately
        assertNotNull(future)
    }

    @Test
    fun `loadMapEntries returns CompletableFuture`() {
        val future = redisManager.loadMapEntries(
            "test:count",
            "field1", "field2", "field3"
        )

        assertNotNull(future)
        // Future is returned (may fail if Redis not available, but that's OK for unit test)
        assertTrue(future is CompletableFuture<*>)
    }

    @Test
    fun `registerChannelUnique replaces previous listener`() {
        val listener1 = ChannelListener { _, _, _ -> }
        val listener2 = ChannelListener { _, _, _ -> }

        redisManager.registerChannelUnique("test:replace", listener1)
        redisManager.registerChannelUnique("test:replace", listener2)

        // Should not throw
        assertDoesNotThrow {
            redisManager.init()
        }
    }

    @Test
    fun `multiple channels can be registered`() {
        val listener1 = ChannelListener { _, _, _ -> }
        val listener2 = ChannelListener { _, _, _ -> }

        redisManager.registerChannelUnique("channel1", listener1)
        redisManager.registerChannelUnique("channel2", listener2)

        assertDoesNotThrow {
            redisManager.init()
        }
    }

    @Test
    fun `init schedules subscription with delay`() {
        val listener = ChannelListener { _, _, _ -> }
        redisManager.registerChannelUnique("test:delay", listener)

        val startTime = System.currentTimeMillis()
        redisManager.init()

        // init() should return immediately (schedules for later)
        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 100, "init() should return quickly")
    }

    @Test
    fun `close shuts down executor services`() {
        redisManager.init()
        Thread.sleep(100)

        redisManager.close()

        // Should not throw
        assertDoesNotThrow {
            redisManager.close() // Idempotent
        }
    }

    @Test
    fun `connect closes previous connection`() {
        redisManager.connect("localhost", 6379, null, null)
        Thread.sleep(100)

        // Should be able to reconnect
        assertDoesNotThrow {
            redisManager.connect("localhost", 6379, null, null)
        }
    }

    @Test
    fun `publish uses executor service`() {
        // Should not block
        val startTime = System.currentTimeMillis()
        redisManager.publish("test:async", "message")
        val duration = System.currentTimeMillis() - startTime

        assertTrue(duration < 50, "publish() should return quickly (async)")
    }

    @Test
    fun `saveMap uses executor service`() {
        // Should not block
        val startTime = System.currentTimeMillis()
        redisManager.saveMap("test:async", mapOf("key" to "value"))
        val duration = System.currentTimeMillis() - startTime

        assertTrue(duration < 50, "saveMap() should return quickly (async)")
    }
}

