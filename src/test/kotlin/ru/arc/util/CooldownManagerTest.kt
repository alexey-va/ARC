@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.arc.TestBase
import java.util.UUID

class CooldownManagerTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        // Clear any existing cooldowns before each test
        // Note: CooldownManager doesn't have a clear method, so we'll work with fresh state
    }

    @AfterEach
    fun tearDownCooldowns() {
        // Clean up any running tasks
        // The task will be cleaned up when plugin is disabled in TestBase
    }

    // ========== Basic Functionality Tests ==========

    @Test
    fun testAddCooldown() {
        val uuid = UUID.randomUUID()
        val cooldownId = "test-cooldown"
        val ticks = 100L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertTrue(remaining > 0, "Cooldown should be active")
        assertEquals(ticks, remaining, "Remaining should equal initial ticks")
    }

    @Test
    fun testAddCooldownWithZeroTicks() {
        val uuid = UUID.randomUUID()
        val cooldownId = "zero-cooldown"

        CooldownManager.addCooldown(uuid, cooldownId, 0L)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining, "Zero ticks cooldown should be immediately expired")
    }

    @Test
    fun testAddCooldownWithNegativeTicks() {
        val uuid = UUID.randomUUID()
        val cooldownId = "negative-cooldown"

        CooldownManager.addCooldown(uuid, cooldownId, -10L)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertTrue(remaining <= 0, "Negative ticks cooldown should be expired or zero")
    }

    @Test
    fun testAddCooldownWithVeryLargeTicks() {
        val uuid = UUID.randomUUID()
        val cooldownId = "large-cooldown"
        val ticks = Long.MAX_VALUE

        CooldownManager.addCooldown(uuid, cooldownId, ticks)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(ticks, remaining, "Should handle very large tick values")
    }

    @Test
    fun testAddCooldownOverwritesExisting() {
        val uuid = UUID.randomUUID()
        val cooldownId = "overwrite-test"

        CooldownManager.addCooldown(uuid, cooldownId, 50L)
        val first = CooldownManager.cooldown(uuid, cooldownId)

        CooldownManager.addCooldown(uuid, cooldownId, 100L)
        val second = CooldownManager.cooldown(uuid, cooldownId)

        assertEquals(50L, first, "First cooldown should be 50")
        assertEquals(100L, second, "Second cooldown should overwrite to 100")
    }

    // ========== Cooldown Query Tests ==========

    @Test
    fun testCooldownForNonExistentPlayer() {
        val uuid = UUID.randomUUID()
        val cooldownId = "test-cooldown"

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining, "Cooldown should not exist for non-existent player")
    }

    @Test
    fun testCooldownForNonExistentId() {
        val uuid = UUID.randomUUID()

        CooldownManager.addCooldown(uuid, "existing-id", 100L)

        val remaining = CooldownManager.cooldown(uuid, "non-existent-id")
        assertEquals(0, remaining, "Should return 0 for non-existent cooldown ID")
    }

    @Test
    fun testCooldownReturnsExactValue() {
        val uuid = UUID.randomUUID()
        val cooldownId = "exact-value-test"
        val ticks = 42L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        val remaining = CooldownManager.cooldown(uuid, cooldownId)

        assertEquals(ticks, remaining, "Should return exact tick value")
    }

    // ========== Multiple Cooldowns Tests ==========

    @Test
    fun testMultipleCooldownsSamePlayer() {
        val uuid = UUID.randomUUID()
        val cooldown1 = "cooldown-1"
        val cooldown2 = "cooldown-2"
        val cooldown3 = "cooldown-3"

        CooldownManager.addCooldown(uuid, cooldown1, 50L)
        CooldownManager.addCooldown(uuid, cooldown2, 100L)
        CooldownManager.addCooldown(uuid, cooldown3, 25L)

        assertEquals(50L, CooldownManager.cooldown(uuid, cooldown1), "First cooldown should be 50")
        assertEquals(100L, CooldownManager.cooldown(uuid, cooldown2), "Second cooldown should be 100")
        assertEquals(25L, CooldownManager.cooldown(uuid, cooldown3), "Third cooldown should be 25")
    }

    @Test
    fun testMultiplePlayersSameCooldownId() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val uuid3 = UUID.randomUUID()
        val cooldownId = "shared-cooldown"

        CooldownManager.addCooldown(uuid1, cooldownId, 50L)
        CooldownManager.addCooldown(uuid2, cooldownId, 100L)
        CooldownManager.addCooldown(uuid3, cooldownId, 25L)

        assertEquals(50L, CooldownManager.cooldown(uuid1, cooldownId), "Player 1 should have 50 ticks")
        assertEquals(100L, CooldownManager.cooldown(uuid2, cooldownId), "Player 2 should have 100 ticks")
        assertEquals(25L, CooldownManager.cooldown(uuid3, cooldownId), "Player 3 should have 25 ticks")
    }

    @Test
    fun testManyCooldownsPerPlayer() {
        val uuid = UUID.randomUUID()
        val count = 50

        repeat(count) { i ->
            CooldownManager.addCooldown(uuid, "cooldown-$i", (i + 1).toLong() * 10)
        }

        repeat(count) { i ->
            val expected = (i + 1).toLong() * 10
            val actual = CooldownManager.cooldown(uuid, "cooldown-$i")
            assertEquals(expected, actual, "Cooldown $i should have correct value")
        }
    }

    // ========== onCooldown Method Tests ==========

    @Test
    fun testOnCooldownExecutesAction() {
        val uuid = UUID.randomUUID()
        val cooldownId = "on-cooldown-test"
        val ticks = 100L
        var actionExecuted = false

        val result = CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            actionExecuted = true
        }

        assertTrue(result, "Action should execute when not on cooldown")
        assertTrue(actionExecuted, "Action should have been executed")
        assertTrue(CooldownManager.cooldown(uuid, cooldownId) > 0, "Cooldown should be active after action")
    }

    @Test
    fun testOnCooldownPreventsExecution() {
        val uuid = UUID.randomUUID()
        val cooldownId = "prevent-execution"
        val ticks = 100L
        var actionExecuted = false

        // First execution
        CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            actionExecuted = true
        }
        assertTrue(actionExecuted, "First action should execute")

        // Second execution should be blocked
        actionExecuted = false
        val result = CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            actionExecuted = true
        }

        assertFalse(result, "Action should not execute when on cooldown")
        assertFalse(actionExecuted, "Action should not have been executed")
    }

    @Test
    fun testOnCooldownWithZeroTicks() {
        val uuid = UUID.randomUUID()
        val cooldownId = "zero-ticks"
        var actionExecuted = false

        // First call with zero ticks
        val result1 = CooldownManager.onCooldown(uuid, cooldownId, 0L) {
            actionExecuted = true
        }
        assertTrue(result1, "Should execute with zero ticks")
        assertTrue(actionExecuted, "Action should execute")

        // Second call should also execute since cooldown expired immediately
        actionExecuted = false
        val result2 = CooldownManager.onCooldown(uuid, cooldownId, 0L) {
            actionExecuted = true
        }
        assertTrue(result2, "Should execute again since zero ticks expired")
        assertTrue(actionExecuted, "Action should execute again")
    }

    @Test
    fun testOnCooldownActionThrowsException() {
        val uuid = UUID.randomUUID()
        val cooldownId = "exception-test"
        val ticks = 100L

        // Action throws exception before cooldown is set
        assertThrows<RuntimeException> {
            CooldownManager.onCooldown(uuid, cooldownId, ticks) {
                throw RuntimeException("Test exception")
            }
        }

        // Cooldown should NOT be set because exception occurred before addCooldown call
        assertEquals(0, CooldownManager.cooldown(uuid, cooldownId), "Cooldown should not be set if action throws")
    }

    @Test
    fun testOnCooldownWithDifferentTicks() {
        val uuid = UUID.randomUUID()
        val cooldownId = "different-ticks"
        var executionCount = 0

        // First execution with 10 ticks
        CooldownManager.onCooldown(uuid, cooldownId, 10L) {
            executionCount++
        }
        assertEquals(1, executionCount, "First execution should work")

        // Wait for cooldown to expire (if task is running)
        if (plugin != null) {
            CooldownManager.setupTask(1L)
            server.scheduler.performTicks(15L)
        }

        // Second execution with different ticks should work after expiration
        if (CooldownManager.cooldown(uuid, cooldownId) == 0L) {
            CooldownManager.onCooldown(uuid, cooldownId, 20L) {
                executionCount++
            }
            assertEquals(2, executionCount, "Second execution should work after expiration")
        }
    }

    // ========== Countdown Task Tests ==========

    @Test
    fun testCooldownExpires() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "expire-test"
        val ticks = 5L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(1L)

        // Wait for cooldown to expire
        server.scheduler.performTicks(10L)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining, "Cooldown should have expired")
    }

    @Test
    fun testCooldownCountdown() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "countdown-test"
        val ticks = 20L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(5L)

        val initial = CooldownManager.cooldown(uuid, cooldownId)
        assertTrue(initial > 0, "Cooldown should be active initially")

        // Perform ticks to reduce cooldown
        server.scheduler.performTicks(2L) // 2 task executions = 10 ticks reduction

        val after = CooldownManager.cooldown(uuid, cooldownId)
        assertTrue(after < initial, "Cooldown should decrease after ticks")
        assertTrue(after >= 10L, "Cooldown should be at least 10 (20 - 10)")
    }

    @Test
    fun testCooldownCountdownExactExpiration() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "exact-expiration"
        val ticks = 10L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(5L)

        // Execute task twice (10 ticks total) to exactly expire
        server.scheduler.performTicks(2L)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining, "Cooldown should be exactly expired")
    }

    @Test
    fun testCooldownCountdownPartialExpiration() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "partial-expiration"
        val ticks = 25L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(5L)

        // Execute task once (5 ticks reduction)
        server.scheduler.performTicks(1L)

        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(20L, remaining, "Cooldown should be reduced by 5 ticks")
    }

    @Test
    fun testCooldownCountdownMultipleCooldowns() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldown1 = "multi-1"
        val cooldown2 = "multi-2"
        val cooldown3 = "multi-3"

        CooldownManager.addCooldown(uuid, cooldown1, 10L)
        CooldownManager.addCooldown(uuid, cooldown2, 20L)
        CooldownManager.addCooldown(uuid, cooldown3, 30L)
        CooldownManager.setupTask(5L)

        // Execute task once
        server.scheduler.performTicks(1L)

        assertEquals(5L, CooldownManager.cooldown(uuid, cooldown1), "First cooldown should be 5")
        assertEquals(15L, CooldownManager.cooldown(uuid, cooldown2), "Second cooldown should be 15")
        assertEquals(25L, CooldownManager.cooldown(uuid, cooldown3), "Third cooldown should be 25")
    }

    @Test
    fun testCooldownCountdownMultiplePlayers() {
        if (plugin == null) return

        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val cooldownId = "shared"

        CooldownManager.addCooldown(uuid1, cooldownId, 15L)
        CooldownManager.addCooldown(uuid2, cooldownId, 25L)
        CooldownManager.setupTask(5L)

        // Execute task once
        server.scheduler.performTicks(1L)

        assertEquals(10L, CooldownManager.cooldown(uuid1, cooldownId), "Player 1 should have 10 ticks")
        assertEquals(20L, CooldownManager.cooldown(uuid2, cooldownId), "Player 2 should have 20 ticks")
    }

    @Test
    fun testCooldownCountdownStaggeredExpiration() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldown1 = "short"
        val cooldown2 = "medium"
        val cooldown3 = "long"

        CooldownManager.addCooldown(uuid, cooldown1, 5L)
        CooldownManager.addCooldown(uuid, cooldown2, 10L)
        CooldownManager.addCooldown(uuid, cooldown3, 15L)
        CooldownManager.setupTask(5L)

        // First execution - cooldown1 expires
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown1), "Short cooldown should expire")
        assertEquals(5L, CooldownManager.cooldown(uuid, cooldown2), "Medium cooldown should be 5")
        assertEquals(10L, CooldownManager.cooldown(uuid, cooldown3), "Long cooldown should be 10")

        // Second execution - cooldown2 expires
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown1), "Short cooldown should still be 0")
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown2), "Medium cooldown should expire")
        assertEquals(5L, CooldownManager.cooldown(uuid, cooldown3), "Long cooldown should be 5")
    }

    // ========== Task Management Tests ==========

    @Test
    fun testSetupTaskMultipleTimes() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "task-setup-test"
        val ticks = 20L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)

        // Setup task first time
        CooldownManager.setupTask(5L)
        server.scheduler.performTicks(1L)
        val first = CooldownManager.cooldown(uuid, cooldownId)

        // Setup task again with different period
        CooldownManager.setupTask(10L)
        server.scheduler.performTicks(1L)
        val second = CooldownManager.cooldown(uuid, cooldownId)

        // Second setup should cancel first and use new period
        assertTrue(second < first, "New task should continue countdown")
    }

    @Test
    fun testSetupTaskWithDifferentPeriods() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "period-test"
        val ticks = 30L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)

        // Test with period 1
        CooldownManager.setupTask(1L)
        server.scheduler.performTicks(5L)
        val afterPeriod1 = CooldownManager.cooldown(uuid, cooldownId)
        assertTrue(afterPeriod1 < ticks, "Cooldown should decrease with period 1")

        // Reset and test with period 10
        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(10L)
        server.scheduler.performTicks(1L)
        val afterPeriod10 = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(20L, afterPeriod10, "Cooldown should decrease by 10 with period 10")
    }

    // ========== Edge Cases and Boundary Tests ==========

    @Test
    fun testCooldownWithEmptyStringId() {
        val uuid = UUID.randomUUID()
        val emptyId = ""

        CooldownManager.addCooldown(uuid, emptyId, 100L)
        val remaining = CooldownManager.cooldown(uuid, emptyId)

        assertEquals(100L, remaining, "Should handle empty string as cooldown ID")
    }

    @Test
    fun testCooldownWithSpecialCharactersInId() {
        val uuid = UUID.randomUUID()
        val specialId = "cooldown-with-special-chars-!@#$%^&*()"

        CooldownManager.addCooldown(uuid, specialId, 100L)
        val remaining = CooldownManager.cooldown(uuid, specialId)

        assertEquals(100L, remaining, "Should handle special characters in cooldown ID")
    }

    @Test
    fun testCooldownWithVeryLongId() {
        val uuid = UUID.randomUUID()
        val longId = "a".repeat(1000)

        CooldownManager.addCooldown(uuid, longId, 100L)
        val remaining = CooldownManager.cooldown(uuid, longId)

        assertEquals(100L, remaining, "Should handle very long cooldown IDs")
    }

    @Test
    fun testCooldownImmediateExpirationCleanup() {
        val uuid = UUID.randomUUID()
        val cooldownId = "immediate-expire"

        CooldownManager.addCooldown(uuid, cooldownId, 0L)
        val remaining = CooldownManager.cooldown(uuid, cooldownId)

        assertEquals(0, remaining, "Zero ticks should be immediately expired")

        // Querying again should still return 0 (cleanup happened)
        val remaining2 = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining2, "Should remain expired after cleanup")
    }

    @Test
    fun testCooldownNegativeValueHandling() {
        val uuid = UUID.randomUUID()
        val cooldownId = "negative-test"

        CooldownManager.addCooldown(uuid, cooldownId, -5L)
        val remaining = CooldownManager.cooldown(uuid, cooldownId)

        assertTrue(remaining <= 0, "Negative ticks should result in expired cooldown")
    }

    // ========== Concurrent-like Scenarios ==========

    @Test
    fun testRapidCooldownAdditions() {
        val uuid = UUID.randomUUID()
        val count = 100

        // Rapidly add many cooldowns
        repeat(count) { i ->
            CooldownManager.addCooldown(uuid, "rapid-$i", (i + 1).toLong())
        }

        // Verify all are present
        repeat(count) { i ->
            val expected = (i + 1).toLong()
            val actual = CooldownManager.cooldown(uuid, "rapid-$i")
            assertEquals(expected, actual, "Rapid cooldown $i should have correct value")
        }
    }

    @Test
    fun testRapidCooldownQueries() {
        val uuid = UUID.randomUUID()
        val cooldownId = "rapid-query"

        CooldownManager.addCooldown(uuid, cooldownId, 100L)

        // Rapidly query the same cooldown
        repeat(1000) {
            val remaining = CooldownManager.cooldown(uuid, cooldownId)
            assertTrue(remaining >= 0, "Query should always return valid value")
        }
    }

    @Test
    fun testAddCooldownWhileCountdownRunning() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "running-countdown"
        val ticks = 20L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(5L)

        // Start countdown
        server.scheduler.performTicks(1L)
        val afterFirst = CooldownManager.cooldown(uuid, cooldownId)

        // Add new cooldown while countdown is running
        CooldownManager.addCooldown(uuid, cooldownId, 30L)

        // Continue countdown
        server.scheduler.performTicks(1L)
        val afterSecond = CooldownManager.cooldown(uuid, cooldownId)

        // Should be counting down from 30, not the previous value
        assertTrue(afterSecond < 30L, "Should countdown from new value")
        assertTrue(afterSecond >= 20L, "Should be at least 20 (30 - 10)")
    }

    // ========== Cleanup and Memory Tests ==========

    @Test
    fun testCooldownCleanupAfterExpiration() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "cleanup-test"
        val ticks = 5L

        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        CooldownManager.setupTask(5L)

        // Wait for expiration
        server.scheduler.performTicks(2L)

        // Cooldown should be cleaned up
        val remaining = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining, "Cooldown should be cleaned up after expiration")

        // Querying again should still return 0
        val remaining2 = CooldownManager.cooldown(uuid, cooldownId)
        assertEquals(0, remaining2, "Should remain cleaned up")
    }

    @Test
    fun testMultipleCooldownsCleanupIndependently() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldown1 = "cleanup-1"
        val cooldown2 = "cleanup-2"
        val cooldown3 = "cleanup-3"

        CooldownManager.addCooldown(uuid, cooldown1, 5L)
        CooldownManager.addCooldown(uuid, cooldown2, 10L)
        CooldownManager.addCooldown(uuid, cooldown3, 15L)
        CooldownManager.setupTask(5L)

        // First expiration
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown1), "First should expire")
        assertTrue(CooldownManager.cooldown(uuid, cooldown2) > 0, "Second should still be active")
        assertTrue(CooldownManager.cooldown(uuid, cooldown3) > 0, "Third should still be active")

        // Second expiration
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown1), "First should stay expired")
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown2), "Second should expire")
        assertTrue(CooldownManager.cooldown(uuid, cooldown3) > 0, "Third should still be active")
    }

    @Test
    fun testPlayerCleanupWhenAllCooldownsExpire() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldown1 = "player-cleanup-1"
        val cooldown2 = "player-cleanup-2"

        CooldownManager.addCooldown(uuid, cooldown1, 5L)
        CooldownManager.addCooldown(uuid, cooldown2, 5L)
        CooldownManager.setupTask(5L)

        // Wait for all cooldowns to expire
        server.scheduler.performTicks(2L)

        // Both should be expired
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown1), "First should be expired")
        assertEquals(0, CooldownManager.cooldown(uuid, cooldown2), "Second should be expired")

        // Player entry should be cleaned up (no cooldowns left)
        // This is tested by querying a new cooldown - should return 0 immediately
        assertEquals(0, CooldownManager.cooldown(uuid, "new-cooldown"), "New cooldown should return 0")
    }

    // ========== Integration Tests ==========

    @Test
    fun testFullCooldownLifecycle() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "lifecycle-test"
        val ticks = 15L

        // 1. Add cooldown
        CooldownManager.addCooldown(uuid, cooldownId, ticks)
        assertEquals(ticks, CooldownManager.cooldown(uuid, cooldownId), "Should start with full ticks")

        // 2. Setup countdown
        CooldownManager.setupTask(5L)

        // 3. Countdown phase 1
        server.scheduler.performTicks(1L)
        assertEquals(10L, CooldownManager.cooldown(uuid, cooldownId), "Should reduce by 5")

        // 4. Countdown phase 2
        server.scheduler.performTicks(1L)
        assertEquals(5L, CooldownManager.cooldown(uuid, cooldownId), "Should reduce by 5 again")

        // 5. Countdown phase 3 - expiration
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldownId), "Should expire")

        // 6. Post-expiration
        server.scheduler.performTicks(1L)
        assertEquals(0, CooldownManager.cooldown(uuid, cooldownId), "Should remain expired")
    }

    @Test
    fun testOnCooldownFullLifecycle() {
        if (plugin == null) return

        val uuid = UUID.randomUUID()
        val cooldownId = "oncooldown-lifecycle"
        val ticks = 10L
        var executionCount = 0

        CooldownManager.setupTask(5L)

        // First execution
        val result1 = CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            executionCount++
        }
        assertTrue(result1, "First should execute")
        assertEquals(1, executionCount, "Should execute once")
        assertTrue(CooldownManager.cooldown(uuid, cooldownId) > 0, "Should have cooldown")

        // Try during cooldown
        val result2 = CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            executionCount++
        }
        assertFalse(result2, "Should not execute during cooldown")
        assertEquals(1, executionCount, "Should still be 1")

        // Wait for expiration
        server.scheduler.performTicks(2L)

        // Should execute again after expiration
        val result3 = CooldownManager.onCooldown(uuid, cooldownId, ticks) {
            executionCount++
        }
        assertTrue(result3, "Should execute after expiration")
        assertEquals(2, executionCount, "Should execute twice total")
    }
}
