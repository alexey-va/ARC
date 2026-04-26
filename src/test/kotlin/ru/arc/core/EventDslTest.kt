package ru.arc.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList

/**
 * Test events for Event DSL testing.
 */
class TestEvent(
    val data: String,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}

class CancellableTestEvent(
    val data: String,
) : Event(),
    Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}

class PriorityTestEvent(
    val data: String,
) : Event() {
    val handlerOrder = mutableListOf<String>()

    override fun getHandlers(): HandlerList = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}

class EventDslTest :
    DescribeSpec({

        describe("TestEventBus") {
            it("should register and fire events") {
                val bus = TestEventBus()
                var received: String? = null

                bus.on<TestEvent> { event ->
                    received = event.data
                }

                bus.fire(TestEvent("hello"))
                received shouldBe "hello"
            }

            it("should support multiple handlers") {
                val bus = TestEventBus()
                var count = 0

                bus.on<TestEvent> { count++ }
                bus.on<TestEvent> { count++ }
                bus.on<TestEvent> { count++ }

                bus.fire(TestEvent("test"))
                count shouldBe 3
            }

            it("should only fire to matching event types") {
                val bus = TestEventBus()
                var testEventCount = 0
                var cancellableEventCount = 0

                bus.on<TestEvent> { testEventCount++ }
                bus.on<CancellableTestEvent> { cancellableEventCount++ }

                bus.fire(TestEvent("test"))
                testEventCount shouldBe 1
                cancellableEventCount shouldBe 0

                bus.fire(CancellableTestEvent("cancel"))
                testEventCount shouldBe 1
                cancellableEventCount shouldBe 1
            }

            it("should unregister handlers") {
                val bus = TestEventBus()
                var count = 0

                val registration = bus.on<TestEvent> { count++ }

                bus.fire(TestEvent("test"))
                count shouldBe 1

                registration.unregister()

                bus.fire(TestEvent("test"))
                count shouldBe 1 // Should not increase
            }

            it("should unregister all handlers") {
                val bus = TestEventBus()
                var count = 0

                bus.on<TestEvent> { count++ }
                bus.on<TestEvent> { count++ }

                bus.fire(TestEvent("test"))
                count shouldBe 2

                bus.unregisterAll()

                bus.fire(TestEvent("test"))
                count shouldBe 2 // Should not increase
            }

            it("should respect event priority") {
                val bus = TestEventBus()
                val order = mutableListOf<String>()

                bus.on<TestEvent>(EventPriority.MONITOR) { order.add("monitor") }
                bus.on<TestEvent>(EventPriority.LOWEST) { order.add("lowest") }
                bus.on<TestEvent>(EventPriority.HIGH) { order.add("high") }
                bus.on<TestEvent>(EventPriority.NORMAL) { order.add("normal") }

                bus.fire(TestEvent("test"))

                order shouldBe listOf("lowest", "normal", "high", "monitor")
            }

            it("should respect ignoreCancelled") {
                val bus = TestEventBus()
                var received = false

                bus.on<CancellableTestEvent>(ignoreCancelled = true) {
                    received = true
                }

                val event = CancellableTestEvent("test")
                event.isCancelled = true

                bus.fire(event)
                received shouldBe false
            }

            it("should fire to non-ignoreCancelled handlers when cancelled") {
                val bus = TestEventBus()
                var received = false

                bus.on<CancellableTestEvent>(ignoreCancelled = false) {
                    received = true
                }

                val event = CancellableTestEvent("test")
                event.isCancelled = true

                bus.fire(event)
                received shouldBe true
            }

            it("should return handler count") {
                val bus = TestEventBus()

                bus.handlerCount() shouldBe 0

                bus.on<TestEvent> {}
                bus.on<TestEvent> {}

                bus.handlerCount() shouldBe 2
            }

            it("should check if handler exists for event type") {
                val bus = TestEventBus()

                bus.hasHandler(TestEvent::class) shouldBe false

                bus.on<TestEvent> {}

                bus.hasHandler(TestEvent::class) shouldBe true
                bus.hasHandler(CancellableTestEvent::class) shouldBe false
            }
        }

        describe("once function") {
            it("should unregister after first event") {
                val bus = TestEventBus()
                var count = 0

                bus.once<TestEvent> { count++ }

                bus.fire(TestEvent("first"))
                count shouldBe 1

                bus.fire(TestEvent("second"))
                count shouldBe 1 // Should not increase
            }
        }

        describe("EventScope") {
            it("should manage multiple registrations") {
                val bus = TestEventBus()
                val scope = EventScope(bus)
                var testCount = 0
                var cancelCount = 0

                scope.on<TestEvent> { testCount++ }
                scope.on<CancellableTestEvent> { cancelCount++ }

                bus.fire(TestEvent("test"))
                bus.fire(CancellableTestEvent("cancel"))

                testCount shouldBe 1
                cancelCount shouldBe 1

                scope.unregisterAll()

                bus.fire(TestEvent("test"))
                bus.fire(CancellableTestEvent("cancel"))

                testCount shouldBe 1 // Should not increase
                cancelCount shouldBe 1
            }

            it("should track registration count") {
                val bus = TestEventBus()
                val scope = EventScope(bus)

                scope.count() shouldBe 0

                scope.on<TestEvent> {}
                scope.on<TestEvent> {}

                scope.count() shouldBe 2

                scope.unregisterAll()
                scope.count() shouldBe 0
            }

            it("should support once in scope") {
                val bus = TestEventBus()
                val scope = EventScope(bus)
                var count = 0

                scope.once<TestEvent> { count++ }

                bus.fire(TestEvent("first"))
                count shouldBe 1

                bus.fire(TestEvent("second"))
                count shouldBe 1

                scope.count() shouldBe 0 // Auto-removed
            }
        }

        describe("EventHandlerBuilder") {
            it("should apply filters") {
                val bus = TestEventBus()
                var received: String? = null

                bus
                    .on<TestEvent>()
                    .filter { it.data.startsWith("hello") }
                    .handler { received = it.data }

                bus.fire(TestEvent("goodbye"))
                received shouldBe null

                bus.fire(TestEvent("hello world"))
                received shouldBe "hello world"
            }

            it("should apply multiple filters") {
                val bus = TestEventBus()
                var received: String? = null

                bus
                    .on<TestEvent>()
                    .filter { it.data.length > 5 }
                    .filter { it.data.contains("test") }
                    .handler { received = it.data }

                bus.fire(TestEvent("test")) // Too short
                received shouldBe null

                bus.fire(TestEvent("long string")) // No "test"
                received shouldBe null

                bus.fire(TestEvent("this is a test"))
                received shouldBe "this is a test"
            }

            it("should support once mode") {
                val bus = TestEventBus()
                var count = 0

                bus
                    .on<TestEvent>()
                    .once()
                    .handler { count++ }

                bus.fire(TestEvent("first"))
                count shouldBe 1

                bus.fire(TestEvent("second"))
                count shouldBe 1
            }

            it("should set priority") {
                val bus = TestEventBus()
                val order = mutableListOf<String>()

                bus
                    .on<TestEvent>()
                    .priority(EventPriority.LOW)
                    .handler { order.add("low") }

                bus
                    .on<TestEvent>()
                    .priority(EventPriority.HIGH)
                    .handler { order.add("high") }

                bus.fire(TestEvent("test"))
                order shouldBe listOf("low", "high")
            }
        }

        describe("Global Events object") {
            it("should use custom bus with withBus") {
                val testBus = TestEventBus()
                var received: String? = null

                Events.withBus(testBus) {
                    on<TestEvent> { received = it.data }
                }

                testBus.fire(TestEvent("hello"))
                received shouldBe "hello"
            }

            it("should create scopes") {
                val testBus = TestEventBus()
                var count = 0

                Events.withBus(testBus) {
                    val scope = eventScope()
                    scope.on<TestEvent> { count++ }

                    testBus.fire(TestEvent("test"))
                    count shouldBe 1

                    scope.unregisterAll()
                }

                testBus.fire(TestEvent("test"))
                count shouldBe 1
            }
        }

        describe("Event registration info") {
            it("should report correct event class") {
                val bus = TestEventBus()
                val registration = bus.on<TestEvent> {}

                registration.eventClass shouldBe TestEvent::class
            }

            it("should report registration status") {
                val bus = TestEventBus()
                val registration = bus.on<TestEvent> {}

                registration.isRegistered shouldBe true

                registration.unregister()

                registration.isRegistered shouldBe false
            }
        }

        describe("Smart once with filters") {
            it("should NOT unregister if filters don't pass") {
                val bus = TestEventBus()
                var received: String? = null

                bus
                    .on<TestEvent>()
                    .filter { it.data == "target" }
                    .once()
                    .handler { received = it.data }

                // Fire non-matching event
                bus.fire(TestEvent("other"))
                received shouldBe null
                bus.handlerCount() shouldBe 1 // Still registered!

                // Fire matching event
                bus.fire(TestEvent("target"))
                received shouldBe "target"
                bus.handlerCount() shouldBe 0 // Now unregistered
            }

            it("should unregister only after filter passes") {
                val bus = TestEventBus()
                var count = 0

                bus
                    .on<TestEvent>()
                    .filter { it.data.length > 5 }
                    .once()
                    .handler { count++ }

                bus.fire(TestEvent("hi")) // Too short - ignored
                bus.fire(TestEvent("hey")) // Too short - ignored
                count shouldBe 0
                bus.handlerCount() shouldBe 1

                bus.fire(TestEvent("hello world")) // Long enough - fires
                count shouldBe 1
                bus.handlerCount() shouldBe 0

                bus.fire(TestEvent("another long one")) // Unregistered - ignored
                count shouldBe 1
            }

            it("should work with multiple filters") {
                val bus = TestEventBus()
                var received: String? = null

                bus
                    .on<TestEvent>()
                    .filter { it.data.startsWith("A") }
                    .filter { it.data.endsWith("Z") }
                    .once()
                    .handler { received = it.data }

                bus.fire(TestEvent("Apple")) // Starts with A, doesn't end with Z
                received shouldBe null
                bus.handlerCount() shouldBe 1

                bus.fire(TestEvent("XYZ")) // Ends with Z, doesn't start with A
                received shouldBe null
                bus.handlerCount() shouldBe 1

                bus.fire(TestEvent("A to Z")) // Starts with A, ends with Z
                received shouldBe "A to Z"
                bus.handlerCount() shouldBe 0
            }
        }

        describe("EventAwaiter") {
            it("should await matching event") {
                val bus = TestEventBus()
                val scheduler = TestTaskScheduler()

                Tasks.withScheduler(scheduler) {
                    Events.withBus(bus) {
                        var result: AwaitResult<TestEvent>? = null

                        awaitEvent<TestEvent>()
                            .filter { it.data == "expected" }
                            .timeout(100)
                            .then { result = it }

                        // Fire non-matching
                        bus.fire(TestEvent("other"))
                        result shouldBe null

                        // Fire matching
                        bus.fire(TestEvent("expected"))
                        result?.isSuccess shouldBe true
                        result?.getOrNull()?.data shouldBe "expected"
                    }
                }
            }

            it("should timeout if no matching event") {
                val bus = TestEventBus()
                val scheduler = TestTaskScheduler()

                Tasks.withScheduler(scheduler) {
                    Events.withBus(bus) {
                        var result: AwaitResult<TestEvent>? = null

                        awaitEvent<TestEvent>()
                            .filter { it.data == "never" }
                            .timeout(50)
                            .then { result = it }

                        // Fire non-matching events
                        bus.fire(TestEvent("other1"))
                        bus.fire(TestEvent("other2"))
                        result shouldBe null

                        // Advance past timeout
                        scheduler.tick(51)
                        result?.isTimeout shouldBe true
                    }
                }
            }

            it("should report cancelled status") {
                val bus = TestEventBus()
                val scheduler = TestTaskScheduler()

                Tasks.withScheduler(scheduler) {
                    Events.withBus(bus) {
                        var result: AwaitResult<TestEvent>? = null

                        val registration =
                            awaitEvent<TestEvent>()
                                .timeout(100)
                                .then { result = it }

                        registration.unregister() // Cancel
                        result?.isCancelled shouldBe true
                    }
                }
            }
        }

        describe("AwaitResult") {
            it("should chain callbacks") {
                var successCalled = false
                var timeoutCalled = false

                val success = AwaitResult.Success(TestEvent("test"))
                success
                    .onSuccess { successCalled = true }
                    .onTimeout { timeoutCalled = true }

                successCalled shouldBe true
                timeoutCalled shouldBe false
            }

            it("should extract value from success") {
                val success = AwaitResult.Success(TestEvent("hello"))
                success.getOrNull()?.data shouldBe "hello"

                val timeout = AwaitResult.Timeout<TestEvent>()
                timeout.getOrNull() shouldBe null
            }
        }
    })
