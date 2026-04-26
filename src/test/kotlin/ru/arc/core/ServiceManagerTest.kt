package ru.arc.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Tests for ServiceManager base class and Lifecycle interface.
 */
class ServiceManagerTest :
    DescribeSpec({

        describe("ServiceManager") {
            it("should initialize service via createService") {
                // Arrange
                val manager = TestManager

                // Act
                manager.init()

                // Assert
                manager.getService() shouldNotBe null
                manager.isInitialized() shouldBe true
                manager.getService()?.started shouldBe true

                // Cleanup
                manager.cancel()
            }

            it("should stop old service when re-initializing") {
                // Arrange
                val manager = TestManager
                manager.init()
                val firstService = manager.getService()

                // Act
                manager.init()

                // Assert
                firstService?.stopped shouldBe true
                manager.getService() shouldNotBe firstService

                // Cleanup
                manager.cancel()
            }

            it("should allow custom service injection for testing") {
                // Arrange
                val manager = TestManager
                val customService = TestService()

                // Act
                manager.init(customService)

                // Assert
                manager.getService() shouldBeSameInstanceAs customService
                customService.started shouldBe true

                // Cleanup
                manager.cancel()
            }

            it("should stop service on cancel") {
                // Arrange
                val manager = TestManager
                manager.init()
                val service = manager.getService()

                // Act
                manager.cancel()

                // Assert
                service?.stopped shouldBe true
                manager.getService() shouldBe null
                manager.isInitialized() shouldBe false
            }

            it("should clear service (alias for cancel)") {
                // Arrange
                val manager = TestManager
                manager.init()

                // Act
                manager.clear()

                // Assert
                manager.getService() shouldBe null
            }

            it("should shutdown service completely") {
                // Arrange
                val manager = TestManager
                manager.init()
                val service = manager.getService()

                // Act
                manager.shutdown()

                // Assert
                service?.shutdownCalled shouldBe true
                manager.getService() shouldBe null
            }

            it("should handle service without Lifecycle interface") {
                // Arrange
                val manager = NonLifecycleManager

                // Act & Assert - should not throw
                manager.init()
                manager.cancel()
                manager.shutdown()
            }

            it("should stop old service when injecting custom service") {
                // Arrange
                val manager = TestManager
                manager.init()
                val firstService = manager.getService()
                val customService = TestService()

                // Act
                manager.init(customService)

                // Assert
                firstService?.stopped shouldBe true
                customService.started shouldBe true

                // Cleanup
                manager.cancel()
            }
        }

        describe("Lifecycle interface") {
            it("should call start, stop, and shutdown in correct order") {
                // Arrange
                val service = TestService()

                // Act
                service.start()
                service.stop()
                service.shutdown()

                // Assert
                service.started shouldBe true
                service.stopped shouldBe true
                service.shutdownCalled shouldBe true
            }
        }
    })

// Test implementations
class TestService : Lifecycle {
    var started = false
    var stopped = false
    var shutdownCalled = false

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
    }

    override fun shutdown() {
        shutdownCalled = true
        stop()
    }
}

object TestManager : ServiceManager<TestService>() {
    override fun createService(): TestService = TestService()
}

// Service without Lifecycle
class NonLifecycleService {
    fun doSomething() = "Hello"
}

object NonLifecycleManager : ServiceManager<NonLifecycleService>() {
    override fun createService(): NonLifecycleService = NonLifecycleService()
}
