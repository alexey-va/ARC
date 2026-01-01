@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.farm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class FarmLogicTest {

    @Nested
    @DisplayName("BlockLimitTracker")
    inner class BlockLimitTrackerTests {

        private lateinit var tracker: BlockLimitTracker
        private val playerId = UUID.randomUUID()

        @BeforeEach
        fun setUp() {
            tracker = BlockLimitTracker(maxBlocks = 100, progressInterval = 10)
        }

        @Nested
        @DisplayName("Limit Checking")
        inner class LimitCheckingTests {

            @Test
            fun `new player has not reached limit`() {
                assertFalse(tracker.hasReachedLimit(playerId))
            }

            @Test
            fun `player reaches limit after max blocks`() {
                repeat(100) { tracker.incrementBlocks(playerId) }

                assertTrue(tracker.hasReachedLimit(playerId))
            }

            @Test
            fun `player below limit has not reached limit`() {
                repeat(99) { tracker.incrementBlocks(playerId) }

                assertFalse(tracker.hasReachedLimit(playerId))
            }
        }

        @Nested
        @DisplayName("Block Counting")
        inner class BlockCountingTests {

            @Test
            fun `new player has zero blocks`() {
                assertEquals(0, tracker.getBlockCount(playerId))
            }

            @Test
            fun `incrementBlocks increases count by one`() {
                tracker.incrementBlocks(playerId)

                assertEquals(1, tracker.getBlockCount(playerId))
            }

            @Test
            fun `incrementBlocks returns new count`() {
                val count1 = tracker.incrementBlocks(playerId)
                val count2 = tracker.incrementBlocks(playerId)
                val count3 = tracker.incrementBlocks(playerId)

                assertEquals(1, count1)
                assertEquals(2, count2)
                assertEquals(3, count3)
            }

            @Test
            fun `different players have separate counts`() {
                val player1 = UUID.randomUUID()
                val player2 = UUID.randomUUID()

                repeat(5) { tracker.incrementBlocks(player1) }
                repeat(10) { tracker.incrementBlocks(player2) }

                assertEquals(5, tracker.getBlockCount(player1))
                assertEquals(10, tracker.getBlockCount(player2))
            }
        }

        @Nested
        @DisplayName("Progress Messages")
        inner class ProgressMessageTests {

            @Test
            fun `should not show progress at zero`() {
                assertFalse(tracker.shouldShowProgress(playerId))
            }

            @Test
            fun `should show progress at interval`() {
                repeat(10) { tracker.incrementBlocks(playerId) }

                assertTrue(tracker.shouldShowProgress(playerId))
            }

            @Test
            fun `should not show progress between intervals`() {
                repeat(5) { tracker.incrementBlocks(playerId) }

                assertFalse(tracker.shouldShowProgress(playerId))
            }

            @Test
            fun `should not show progress at max`() {
                repeat(100) { tracker.incrementBlocks(playerId) }

                // At max, don't show progress
                assertFalse(tracker.shouldShowProgress(playerId))
            }

            @Test
            fun `should show progress at multiples of interval`() {
                repeat(20) { tracker.incrementBlocks(playerId) }
                assertTrue(tracker.shouldShowProgress(playerId))

                repeat(10) { tracker.incrementBlocks(playerId) } // Now 30
                assertTrue(tracker.shouldShowProgress(playerId))
            }
        }

        @Nested
        @DisplayName("Reset")
        inner class ResetTests {

            @Test
            fun `resetAll clears all players`() {
                val player1 = UUID.randomUUID()
                val player2 = UUID.randomUUID()

                repeat(50) { tracker.incrementBlocks(player1) }
                repeat(30) { tracker.incrementBlocks(player2) }

                tracker.resetAll()

                assertEquals(0, tracker.getBlockCount(player1))
                assertEquals(0, tracker.getBlockCount(player2))
            }

            @Test
            fun `reset clears specific player`() {
                val player1 = UUID.randomUUID()
                val player2 = UUID.randomUUID()

                repeat(50) { tracker.incrementBlocks(player1) }
                repeat(30) { tracker.incrementBlocks(player2) }

                tracker.reset(player1)

                assertEquals(0, tracker.getBlockCount(player1))
                assertEquals(30, tracker.getBlockCount(player2))
            }
        }

        @Nested
        @DisplayName("Get All Counts")
        inner class GetAllCountsTests {

            @Test
            fun `getAllCounts returns empty map initially`() {
                assertTrue(tracker.getAllCounts().isEmpty())
            }

            @Test
            fun `getAllCounts returns all player counts`() {
                val player1 = UUID.randomUUID()
                val player2 = UUID.randomUUID()

                repeat(5) { tracker.incrementBlocks(player1) }
                repeat(15) { tracker.incrementBlocks(player2) }

                val counts = tracker.getAllCounts()

                assertEquals(2, counts.size)
                assertEquals(5, counts[player1])
                assertEquals(15, counts[player2])
            }
        }
    }

    @Nested
    @DisplayName("TemporaryBlockTracker")
    inner class TemporaryBlockTrackerTests {

        private var currentTime = 0L
        private lateinit var tracker: TemporaryBlockTracker<String>

        @BeforeEach
        fun setUp() {
            currentTime = 0L
            tracker = TemporaryBlockTracker(
                expireTimeMs = 1000L,
                timeProvider = { currentTime }
            )
        }

        @Test
        fun `new tracker is empty`() {
            assertEquals(0, tracker.count())
        }

        @Test
        fun `add increases count`() {
            tracker.add("block1")
            tracker.add("block2")

            assertEquals(2, tracker.count())
        }

        @Test
        fun `isTracked returns true for added blocks`() {
            tracker.add("block1")

            assertTrue(tracker.isTracked("block1"))
            assertFalse(tracker.isTracked("block2"))
        }

        @Test
        fun `getExpired returns nothing before expiration`() {
            tracker.add("block1")
            currentTime = 999L

            val expired = tracker.getExpired()

            assertTrue(expired.isEmpty())
            assertTrue(tracker.isTracked("block1"))
        }

        @Test
        fun `getExpired returns blocks after expiration`() {
            tracker.add("block1")
            currentTime = 1001L

            val expired = tracker.getExpired()

            assertEquals(listOf("block1"), expired)
            assertFalse(tracker.isTracked("block1"))
        }

        @Test
        fun `getExpired removes only expired blocks`() {
            tracker.add("block1")
            currentTime = 500L
            tracker.add("block2")

            currentTime = 1001L // block1 expired (added at 0), block2 not (added at 500)

            val expired = tracker.getExpired()

            assertEquals(listOf("block1"), expired)
            assertTrue(tracker.isTracked("block2"))
        }

        @Test
        fun `clear removes all blocks`() {
            tracker.add("block1")
            tracker.add("block2")

            tracker.clear()

            assertEquals(0, tracker.count())
        }
    }

    @Nested
    @DisplayName("RegionBounds")
    inner class RegionBoundsTests {

        private val bounds = RegionBounds(
            minX = 0, minY = 0, minZ = 0,
            maxX = 10, maxY = 10, maxZ = 10
        )

        @Test
        fun `contains returns true for points inside`() {
            assertTrue(bounds.contains(5, 5, 5))
            assertTrue(bounds.contains(0, 0, 0))
            assertTrue(bounds.contains(10, 10, 10))
        }

        @Test
        fun `contains returns false for points outside`() {
            assertFalse(bounds.contains(-1, 5, 5))
            assertFalse(bounds.contains(5, -1, 5))
            assertFalse(bounds.contains(5, 5, -1))
            assertFalse(bounds.contains(11, 5, 5))
            assertFalse(bounds.contains(5, 11, 5))
            assertFalse(bounds.contains(5, 5, 11))
        }

        @Test
        fun `volume is calculated correctly`() {
            assertEquals(11L * 11L * 11L, bounds.volume())
        }

        @Test
        fun `single block region has volume 1`() {
            val singleBlock = RegionBounds(5, 5, 5, 5, 5, 5)
            assertEquals(1L, singleBlock.volume())
        }
    }
}

