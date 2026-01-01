@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TimeProviderTest {

    @Nested
    @DisplayName("SystemTimeProvider")
    inner class SystemTimeProviderTests {

        @Test
        fun `currentTimeMillis returns system time`() {
            val before = System.currentTimeMillis()
            val result = SystemTimeProvider.currentTimeMillis()
            val after = System.currentTimeMillis()

            assertTrue(result >= before)
            assertTrue(result <= after)
        }

        @Test
        fun `nanoTime returns system nanos`() {
            val before = System.nanoTime()
            val result = SystemTimeProvider.nanoTime()
            val after = System.nanoTime()

            assertTrue(result >= before)
            assertTrue(result <= after)
        }
    }

    @Nested
    @DisplayName("TestTimeProvider")
    inner class TestTimeProviderTests {

        @Test
        fun `starts at zero by default`() {
            val provider = TestTimeProvider()
            assertEquals(0L, provider.currentTimeMillis())
        }

        @Test
        fun `starts at specified time`() {
            val provider = TestTimeProvider(1000L)
            assertEquals(1000L, provider.currentTimeMillis())
        }

        @Test
        fun `setTime changes current time`() {
            val provider = TestTimeProvider()
            provider.setTime(5000L)
            assertEquals(5000L, provider.currentTimeMillis())
        }

        @Test
        fun `advance adds to current time`() {
            val provider = TestTimeProvider(1000L)
            provider.advance(500L)
            assertEquals(1500L, provider.currentTimeMillis())
        }

        @Test
        fun `advanceTicks converts ticks to millis`() {
            val provider = TestTimeProvider(0L)
            provider.advanceTicks(20) // 20 ticks = 1 second = 1000ms
            assertEquals(1000L, provider.currentTimeMillis())
        }

        @Test
        fun `nanoTime reflects millis`() {
            val provider = TestTimeProvider(1000L)
            // 1000ms = 1_000_000_000ns
            assertEquals(1_000_000_000L, provider.nanoTime())
        }
    }
}

