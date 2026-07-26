package ru.arc.network

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.hooks.HookRegistry
import ru.arc.redis.ChannelListener
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations

class NetworkRegistryLifecycleTest {
    @AfterEach
    fun tearDown() {
        NetworkRegistry.landsMessager?.close()
        NetworkRegistry.landsMessager = null
        HookRegistry.auctionHook = null
    }

    @Test
    fun `init and close own every registered listener`() {
        Tasks.withScheduler(TestTaskScheduler()) {
            val redis = InMemoryRedis()
            val registry = NetworkRegistry(redis)

            registry.init()
            registry.init()

            assertEquals(1, redis.listenerCount("arc.proxy_player_list"))
            assertEquals(1, redis.listenerCount("arc.lands_req"))
            assertEquals(1, redis.listenerCount("arc.lands_response"))
            assertEquals(1, redis.listenerCount("arc.high_lows_update"))

            registry.close()

            assertEquals(0, redis.listenerCount("arc.proxy_player_list"))
            assertEquals(0, redis.listenerCount("arc.lands_req"))
            assertEquals(0, redis.listenerCount("arc.lands_response"))
            assertEquals(0, redis.listenerCount("arc.high_lows_update"))
            assertNull(NetworkRegistry.landsMessager)
        }
    }

    @Test
    fun `partial init failure unregisters listeners already installed`() {
        Tasks.withScheduler(TestTaskScheduler()) {
            val redis = FailingRegistrationRedis(failAt = 3)
            val registry = NetworkRegistry(redis)

            assertThrows(IllegalStateException::class.java) {
                registry.init()
            }

            assertEquals(0, redis.delegate.listenerCount("arc.proxy_player_list"))
            assertEquals(0, redis.delegate.listenerCount("arc.lands_response"))
            assertNull(NetworkRegistry.landsMessager)
        }
    }
}

private class FailingRegistrationRedis(
    private val failAt: Int,
    val delegate: InMemoryRedis = InMemoryRedis(),
) : RedisOperations by delegate {
    private var registrations = 0

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        registrations++
        if (registrations == failAt) throw IllegalStateException("registration failed")
        delegate.registerChannelUnique(channel, listener)
    }
}
