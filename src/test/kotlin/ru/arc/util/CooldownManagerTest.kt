@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.core.TestTimeProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CooldownManagerTest {

    @Nested
    @DisplayName("TestCooldownProvider")
    inner class TestCooldownProviderTests {

        private lateinit var timeProvider: TestTimeProvider
        private lateinit var cooldownProvider: TestCooldownProvider
        private val testUuid = UUID.randomUUID()

        @BeforeEach
        fun setUp() {
            timeProvider = TestTimeProvider(0L)
            cooldownProvider = TestCooldownProvider(timeProvider)
        }

        @Test
        fun `no cooldown by default`() {
            assertEquals(0L, cooldownProvider.cooldown(testUuid, "test"))
            assertFalse(cooldownProvider.isOnCooldown(testUuid, "test"))
        }

        @Test
        fun `addCooldown creates cooldown`() {
            cooldownProvider.addCooldown(testUuid, "test", 100) // 100 ticks = 5000ms

            assertTrue(cooldownProvider.isOnCooldown(testUuid, "test"))
            assertEquals(100L, cooldownProvider.cooldown(testUuid, "test"))
        }

        @Test
        fun `cooldown decreases with time`() {
            cooldownProvider.addCooldown(testUuid, "test", 100) // 5000ms

            timeProvider.advance(2500) // 50 ticks

            assertEquals(50L, cooldownProvider.cooldown(testUuid, "test"))
        }

        @Test
        fun `cooldown expires after full time`() {
            cooldownProvider.addCooldown(testUuid, "test", 100) // 5000ms

            timeProvider.advance(5000)

            assertEquals(0L, cooldownProvider.cooldown(testUuid, "test"))
            assertFalse(cooldownProvider.isOnCooldown(testUuid, "test"))
        }

        @Test
        fun `onCooldown executes action when not on cooldown`() {
            val counter = AtomicInteger(0)

            val executed = cooldownProvider.onCooldown(testUuid, "test", 100) {
                counter.incrementAndGet()
            }

            assertTrue(executed)
            assertEquals(1, counter.get())
        }

        @Test
        fun `onCooldown skips action when on cooldown`() {
            cooldownProvider.addCooldown(testUuid, "test", 100)
            val counter = AtomicInteger(0)

            val executed = cooldownProvider.onCooldown(testUuid, "test", 100) {
                counter.incrementAndGet()
            }

            assertFalse(executed)
            assertEquals(0, counter.get())
        }

        @Test
        fun `onCooldown adds cooldown after action`() {
            cooldownProvider.onCooldown(testUuid, "test", 50) {}

            assertTrue(cooldownProvider.isOnCooldown(testUuid, "test"))
        }

        @Test
        fun `clearCooldown removes specific cooldown`() {
            cooldownProvider.addCooldown(testUuid, "test1", 100)
            cooldownProvider.addCooldown(testUuid, "test2", 100)

            cooldownProvider.clearCooldown(testUuid, "test1")

            assertFalse(cooldownProvider.isOnCooldown(testUuid, "test1"))
            assertTrue(cooldownProvider.isOnCooldown(testUuid, "test2"))
        }

        @Test
        fun `clearAllCooldowns removes all for player`() {
            cooldownProvider.addCooldown(testUuid, "test1", 100)
            cooldownProvider.addCooldown(testUuid, "test2", 100)

            cooldownProvider.clearAllCooldowns(testUuid)

            assertFalse(cooldownProvider.isOnCooldown(testUuid, "test1"))
            assertFalse(cooldownProvider.isOnCooldown(testUuid, "test2"))
        }

        @Test
        fun `different players have separate cooldowns`() {
            val uuid1 = UUID.randomUUID()
            val uuid2 = UUID.randomUUID()

            cooldownProvider.addCooldown(uuid1, "test", 100)

            assertTrue(cooldownProvider.isOnCooldown(uuid1, "test"))
            assertFalse(cooldownProvider.isOnCooldown(uuid2, "test"))
        }
    }

    @Nested
    @DisplayName("CooldownManager with TestScheduler")
    inner class CooldownManagerWithSchedulerTests {

        private lateinit var scheduler: TestTaskScheduler
        private val testUuid = UUID.randomUUID()

        @BeforeEach
        fun setUp() {
            scheduler = TestTaskScheduler()
            CooldownManager.clearAll()
            CooldownManager.stop()
        }

        @Test
        fun `setupTask with custom scheduler`() {
            CooldownManager.setupTask(20, scheduler)

            assertEquals(1, scheduler.timerCount())
        }

        @Test
        fun `cooldowns tick down with scheduler`() {
            CooldownManager.setupTask(20, scheduler)
            CooldownManager.addCooldown(testUuid, "test", 100)

            // Timer fires after delay (20 ticks), so tick 20 times
            scheduler.tick(20)

            // Cooldown should decrease by 20 ticks
            assertEquals(80L, CooldownManager.cooldown(testUuid, "test"))
        }

        @Test
        fun `stop cancels the task`() {
            CooldownManager.setupTask(20, scheduler)

            CooldownManager.stop()

            // Task should be cancelled (timer won't execute)
            CooldownManager.addCooldown(testUuid, "test", 100)
            scheduler.tick(100)

            // Cooldown unchanged because task is cancelled
            assertEquals(100L, CooldownManager.cooldown(testUuid, "test"))
        }

        @Test
        fun `asProvider returns CooldownProvider interface`() {
            val provider = CooldownManager.asProvider()

            assertNotNull(provider)
            assertTrue(provider is CooldownProvider)
        }
    }
}
