package ru.arc.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent
import ru.arc.KotestTestBase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Tests for concurrency fixes in EventDsl:
 * 1. AtomicBoolean flag in EventAwaiter
 * 2. Safe reference handling in once()
 */
class EventDslConcurrencyTest :
    KotestTestBase({

        describe("EventAwaiter with AtomicBoolean") {
            it("should call callback exactly once when timeout and event race") {
                // Arrange
                val bus = TestEventBus()
                val player = server.addPlayer("testplayer")
                val callCounts = ConcurrentHashMap<Int, AtomicInteger>()
                val latch = CountDownLatch(50)

                // Act - Create 50 awaiters that will race between timeout and event
                repeat(50) { i ->
                    callCounts[i] = AtomicInteger(0)
                    thread {
                        val awaiter =
                            EventAwaiter(bus, TestPlayerEvent::class)
                                .timeout(10) // Short timeout to create race condition

                        awaiter.then { result ->
                            // This should only be called ONCE per awaiter due to AtomicBoolean
                            callCounts[i]!!.incrementAndGet()
                            latch.countDown()
                        }

                        // Fire event after small delay to race with timeout
                        Thread.sleep(5)
                        bus.fire(TestPlayerEvent(player))
                    }
                }

                // Assert
                latch.await(10, TimeUnit.SECONDS) shouldBe true
                // Verify each awaiter's callback was called exactly once (not twice due to race)
                callCounts.values.forEach { count ->
                    count.get() shouldBe 1
                }
            }

            it("should not trigger both timeout and success") {
                // Arrange
                val bus = TestEventBus()
                val callCount = AtomicInteger(0)

                // Act
                val awaiter =
                    EventAwaiter(bus, TestPlayerEvent::class)
                        .timeout(10)

                awaiter.then { result ->
                    callCount.incrementAndGet()
                    result shouldNotBe null // Should only be called once
                }

                // Fire event immediately (race condition test)
                val player = server.addPlayer("test")
                bus.fire(TestPlayerEvent(player))

                // Wait a bit for timeout task to trigger if it will
                Thread.sleep(20)

                // Assert - Callback should only be called once
                callCount.get() shouldBe 1
            }
        }

        describe("once() with safe references") {
            it("should not throw when event fires during registration") {
                // Arrange
                val bus = TestEventBus()
                val scope = EventScope(bus)
                var handlerCalled = false

                // Act - Register once() and fire event immediately
                scope.once<TestPlayerEvent> { event ->
                    handlerCalled = true
                }

                val player = server.addPlayer("test")
                bus.fire(TestPlayerEvent(player))

                // Assert
                handlerCalled shouldBe true
                scope.count() shouldBe 0 // Should auto-unregister
            }

            it("should handle rapid fire-unregister cycles") {
                // Arrange
                val bus = TestEventBus()
                val callCounts = ConcurrentHashMap<Int, AtomicInteger>()
                val registerLatch = CountDownLatch(50)
                val fireLatch = CountDownLatch(50)

                // Pre-create player on main thread (MockBukkit requirement)
                val player = server.addPlayer("testplayer")

                // Step 1: Register all 50 once() handlers concurrently
                repeat(50) { i ->
                    callCounts[i] = AtomicInteger(0)
                    thread {
                        val scope = EventScope(bus)
                        scope.once<TestPlayerEvent> { event ->
                            // Should only be called once per handler
                            callCounts[i]!!.incrementAndGet()
                        }
                        registerLatch.countDown()
                    }
                }

                // Wait for all registrations
                registerLatch.await(5, TimeUnit.SECONDS)

                // Step 2: Fire 50 events concurrently
                repeat(50) {
                    thread {
                        Thread.sleep(1) // Tiny delay for thread spread
                        bus.fire(TestPlayerEvent(player))
                        fireLatch.countDown()
                    }
                }

                // Wait for all events
                fireLatch.await(5, TimeUnit.SECONDS)

                // Give handlers time to process
                Thread.sleep(100)

                // Assert - Each once() handler should fire exactly once despite 50 events
                callCounts.values.forEach { count ->
                    count.get() shouldBe 1
                }
            }

            it("should unregister in EventScope without errors") {
                // Arrange
                val bus = TestEventBus()
                val scope = EventScope(bus)
                val handleCount = AtomicInteger(0)

                // Act - Multiple once() in scope
                repeat(10) {
                    scope.once<TestPlayerEvent> { event ->
                        handleCount.incrementAndGet()
                    }
                }

                val player = server.addPlayer("test")
                bus.fire(TestPlayerEvent(player))

                // Assert
                handleCount.get() shouldBe 10
                scope.count() shouldBe 0 // All should auto-unregister
            }
        }

        describe("Concurrent event registration and unregistration") {
            it("should handle concurrent on() calls") {
                // Arrange
                val bus = TestEventBus()
                val handleCount = AtomicInteger(0)
                val registrations = mutableListOf<EventRegistration>()

                // Act - 50 threads registering listeners
                runBlocking {
                    repeat(50) {
                        launch {
                            val reg =
                                bus.on<TestPlayerEvent> { event ->
                                    handleCount.incrementAndGet()
                                }
                            synchronized(registrations) {
                                registrations.add(reg)
                            }
                        }
                    }
                }

                val player = server.addPlayer("test")
                bus.fire(TestPlayerEvent(player))

                // Assert
                handleCount.get() shouldBe 50
                registrations.forEach { it.unregister() }
            }
        }
    })

// Test event
class TestPlayerEvent(
    player: Player,
) : PlayerEvent(player) {
    companion object {
        private val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST
}
