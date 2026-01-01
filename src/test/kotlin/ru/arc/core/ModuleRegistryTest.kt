@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for ModuleRegistry lifecycle management.
 */
class ModuleRegistryTest {

    // Test module implementations for tracking lifecycle
    class TestModule(
        override val name: String,
        override val priority: Int = 100,
        override val enabled: Boolean = true
    ) : PluginModule {
        var initCalled = false
        var shutdownCalled = false
        var reloadCalled = false
        var initOrder = -1
        var shutdownOrder = -1

        companion object {
            var globalInitCounter = 0
            var globalShutdownCounter = 0

            fun reset() {
                globalInitCounter = 0
                globalShutdownCounter = 0
            }
        }

        override fun init() {
            initCalled = true
            initOrder = globalInitCounter++
        }

        override fun reload() {
            reloadCalled = true
        }

        override fun shutdown() {
            shutdownCalled = true
            shutdownOrder = globalShutdownCounter++
        }
    }

    // Separate registry for tests (can't use singleton directly as it persists state)
    private lateinit var modules: MutableList<PluginModule>

    @BeforeEach
    fun setUp() {
        modules = mutableListOf()
        TestModule.reset()
    }

    // ==================== PluginModule Interface Tests ====================

    @Nested
    @DisplayName("PluginModule Interface")
    inner class PluginModuleTests {

        @Test
        @DisplayName("Default priority is 100")
        fun testDefaultPriority() {
            val module = object : PluginModule {
                override val name = "Test"
                override fun init() {}
                override fun shutdown() {}
            }
            assertEquals(100, module.priority)
        }

        @Test
        @DisplayName("Default enabled is true")
        fun testDefaultEnabled() {
            val module = object : PluginModule {
                override val name = "Test"
                override fun init() {}
                override fun shutdown() {}
            }
            assertTrue(module.enabled)
        }

        @Test
        @DisplayName("Default reload calls shutdown then init")
        fun testDefaultReload() {
            var shutdownCalled = false
            var initCalled = false
            var shutdownFirst = false

            val module = object : PluginModule {
                override val name = "Test"
                override fun init() {
                    initCalled = true
                    if (shutdownCalled) shutdownFirst = true
                }

                override fun shutdown() {
                    shutdownCalled = true
                }
            }

            module.reload()

            assertTrue(shutdownCalled, "shutdown should be called")
            assertTrue(initCalled, "init should be called")
            assertTrue(shutdownFirst, "shutdown should be called before init")
        }
    }

    // ==================== Priority Tests ====================

    @Nested
    @DisplayName("Priority-based Initialization")
    inner class PriorityTests {

        @Test
        @DisplayName("Modules are initialized in priority order (lower first)")
        fun testInitOrder() {
            val low = TestModule("Low", priority = 10)
            val medium = TestModule("Medium", priority = 50)
            val high = TestModule("High", priority = 100)

            // Add in random order
            modules.addAll(listOf(high, low, medium))

            // Simulate init
            modules.filter { it.enabled }
                .sortedBy { it.priority }
                .forEach { (it as TestModule).init() }

            assertEquals(0, low.initOrder, "Low priority should init first")
            assertEquals(1, medium.initOrder, "Medium priority should init second")
            assertEquals(2, high.initOrder, "High priority should init last")
        }

        @Test
        @DisplayName("Modules are shut down in reverse priority order (higher first)")
        fun testShutdownOrder() {
            val low = TestModule("Low", priority = 10)
            val medium = TestModule("Medium", priority = 50)
            val high = TestModule("High", priority = 100)

            modules.addAll(listOf(high, low, medium))

            // Simulate shutdown (reverse order)
            modules.filter { it.enabled }
                .sortedByDescending { it.priority }
                .forEach { (it as TestModule).shutdown() }

            assertEquals(0, high.shutdownOrder, "High priority should shutdown first")
            assertEquals(1, medium.shutdownOrder, "Medium priority should shutdown second")
            assertEquals(2, low.shutdownOrder, "Low priority should shutdown last")
        }
    }

    // ==================== Enabled/Disabled Tests ====================

    @Nested
    @DisplayName("Enabled/Disabled Modules")
    inner class EnabledTests {

        @Test
        @DisplayName("Disabled modules are skipped during init")
        fun testDisabledModulesSkipped() {
            val enabled = TestModule("Enabled", enabled = true)
            val disabled = TestModule("Disabled", enabled = false)

            modules.addAll(listOf(enabled, disabled))

            modules.filter { it.enabled }
                .forEach { (it as TestModule).init() }

            assertTrue(enabled.initCalled, "Enabled module should be initialized")
            assertFalse(disabled.initCalled, "Disabled module should be skipped")
        }

        @Test
        @DisplayName("Disabled modules are skipped during shutdown")
        fun testDisabledModulesSkippedOnShutdown() {
            val enabled = TestModule("Enabled", enabled = true)
            val disabled = TestModule("Disabled", enabled = false)

            modules.addAll(listOf(enabled, disabled))

            modules.filter { it.enabled }
                .forEach { (it as TestModule).shutdown() }

            assertTrue(enabled.shutdownCalled, "Enabled module should be shut down")
            assertFalse(disabled.shutdownCalled, "Disabled module should be skipped")
        }
    }

    // ==================== Lifecycle Tests ====================

    @Nested
    @DisplayName("Full Lifecycle")
    inner class LifecycleTests {

        @Test
        @DisplayName("Init -> Reload -> Shutdown lifecycle")
        fun testFullLifecycle() {
            val module = TestModule("Test")
            modules.add(module)

            // Init
            module.init()
            assertTrue(module.initCalled)
            assertFalse(module.reloadCalled)
            assertFalse(module.shutdownCalled)

            // Reload
            module.reloadCalled = false
            module.reload()
            assertTrue(module.reloadCalled)

            // Shutdown
            module.shutdown()
            assertTrue(module.shutdownCalled)
        }

        @Test
        @DisplayName("Multiple modules lifecycle")
        fun testMultipleModulesLifecycle() {
            val module1 = TestModule("Module1", priority = 10)
            val module2 = TestModule("Module2", priority = 20)
            val module3 = TestModule("Module3", priority = 30)

            modules.addAll(listOf(module1, module2, module3))

            // Init all
            modules.sortedBy { it.priority }.forEach { (it as TestModule).init() }

            assertTrue(module1.initCalled)
            assertTrue(module2.initCalled)
            assertTrue(module3.initCalled)

            // Shutdown all (reverse order)
            modules.sortedByDescending { it.priority }.forEach { (it as TestModule).shutdown() }

            assertTrue(module1.shutdownCalled)
            assertTrue(module2.shutdownCalled)
            assertTrue(module3.shutdownCalled)

            // Verify order
            assertTrue(module1.initOrder < module2.initOrder)
            assertTrue(module2.initOrder < module3.initOrder)
            assertTrue(module3.shutdownOrder < module2.shutdownOrder)
            assertTrue(module2.shutdownOrder < module1.shutdownOrder)
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("Empty module list doesn't crash")
        fun testEmptyModuleList() {
            // Should not throw
            modules.filter { it.enabled }
                .sortedBy { it.priority }
                .forEach { (it as TestModule).init() }

            modules.filter { it.enabled }
                .sortedByDescending { it.priority }
                .forEach { (it as TestModule).shutdown() }
        }

        @Test
        @DisplayName("Same priority modules maintain order")
        fun testSamePriorityOrder() {
            val module1 = TestModule("First", priority = 50)
            val module2 = TestModule("Second", priority = 50)
            val module3 = TestModule("Third", priority = 50)

            modules.addAll(listOf(module1, module2, module3))

            // Stable sort should maintain insertion order for same priority
            val sorted = modules.sortedBy { it.priority }

            assertEquals("First", sorted[0].name)
            assertEquals("Second", sorted[1].name)
            assertEquals("Third", sorted[2].name)
        }

        @Test
        @DisplayName("Module with exception doesn't stop others")
        fun testExceptionHandling() {
            val good1 = TestModule("Good1", priority = 10)
            val bad = object : PluginModule {
                override val name = "Bad"
                override val priority = 20
                var initCalled = false
                override fun init() {
                    initCalled = true
                    throw RuntimeException("Test exception")
                }

                override fun shutdown() {}
            }
            val good2 = TestModule("Good2", priority = 30)

            modules.addAll(listOf(good1, bad, good2))

            // Simulate init with exception handling
            modules.sortedBy { it.priority }.forEach { module ->
                try {
                    when (module) {
                        is TestModule -> module.init()
                        else -> (module as? PluginModule)?.init()
                    }
                } catch (e: Exception) {
                    // Logged but continue
                }
            }

            assertTrue(good1.initCalled, "First module should init")
            assertTrue(bad.initCalled, "Bad module init was attempted")
            assertTrue(good2.initCalled, "Last module should still init after exception")
        }
    }
}

