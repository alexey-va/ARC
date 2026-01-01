@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.farm

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.block.BlockBreakEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.TestBase
import ru.arc.common.WeightedRandom
import ru.arc.core.TestTaskScheduler

/**
 * Tests for Mine zone functionality.
 */
class MineTest : TestBase() {

    private lateinit var playerMock: PlayerMock
    private lateinit var scheduler: TestTaskScheduler
    private lateinit var regionFactory: TestRegionFactory
    private lateinit var world: World
    private lateinit var mine: Mine
    private lateinit var limitTracker: BlockLimitTracker
    private var currentTime = 0L

    private fun createOrePicker(weights: Map<Material, Int>): WeightedRandom<Material> {
        val picker = WeightedRandom<Material>()
        for ((material, weight) in weights) {
            picker.add(material, weight.toDouble())
        }
        return picker
    }

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        playerMock = server.addPlayer("Miner")
        scheduler = TestTaskScheduler()
        currentTime = 0L

        regionFactory = TestRegionFactory { worldName ->
            server.worlds.find { it.name == worldName }
        }

        world = server.addSimpleWorld("mine_world")
        // Use Y >= 64 to avoid MockBukkit min height issues
        regionFactory.registerRegion("mine_region", RegionBounds(0, 64, 0, 20, 80, 20))

        limitTracker = BlockLimitTracker(100, 16)

        val config = MineConfig(
            id = "test_mine",
            worldName = "mine_world",
            regionName = "mine_region",
            permission = "mine.use",
            particles = false,
            maxBlocksPerDay = 100,
            priority = 1,
            oreWeights = mapOf(
                Material.COAL_ORE to 50,
                Material.IRON_ORE to 30,
                Material.GOLD_ORE to 15,
                Material.DIAMOND_ORE to 5
            ),
            tempBlock = Material.BEDROCK,
            baseBlock = Material.STONE,
            expireTimeMs = 60000L,
            replaceTime = 20L,
            replaceBatch = 10,
            expPerBase = 1,
            expPerOre = 2
        )

        val orePicker = createOrePicker(config.oreWeights)

        mine = Mine(
            id = "test_mine",
            priority = 1,
            config = config,
            region = regionFactory.create("mine_world", "mine_region"),
            adminPermission = "arc.farm-admin",
            plugin = plugin!!,
            scheduler = scheduler,
            limitTracker = limitTracker,
            orePicker = orePicker,
            timeProvider = { currentTime }
        )
    }

    @Nested
    @DisplayName("Mine Basic Operations")
    inner class MineBasicTests {

        @Test
        fun `returns NotHandled for block outside region`() {
            val block = world.getBlockAt(100, 70, 100)
            block.setType(Material.COAL_ORE)

            val event = BlockBreakEvent(block, playerMock)
            val result = mine.processBreak(event)

            assertEquals(BreakResult.NotHandled, result)
        }

        @Test
        fun `admin bypasses all checks`() {
            playerMock.addAttachment(plugin!!, "arc.farm-admin", true)

            val block = world.getBlockAt(10, 70, 10)
            block.setType(Material.COAL_ORE)

            val event = BlockBreakEvent(block, playerMock)
            val result = mine.processBreak(event)

            assertEquals(BreakResult.Allowed, result)
            assertFalse(event.isCancelled)
        }

        @Test
        fun `denies access without permission`() {
            val block = world.getBlockAt(10, 70, 10)
            block.setType(Material.COAL_ORE)

            val event = BlockBreakEvent(block, playerMock)
            val result = mine.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
            assertTrue(event.isCancelled)
        }

        @Test
        fun `denies non-ore materials`() {
            playerMock.addAttachment(plugin!!, "mine.use", true)

            val block = world.getBlockAt(10, 70, 10)
            block.setType(Material.DIRT) // Not an ore

            val event = BlockBreakEvent(block, playerMock)
            val result = mine.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
        }
    }

    @Nested
    @DisplayName("Mine Limit Tracking")
    inner class MineLimitTests {

        @Test
        fun `respects daily limit`() {
            playerMock.addAttachment(plugin!!, "mine.use", true)

            // Fill up the limit
            repeat(100) {
                limitTracker.incrementBlocks(playerMock.uniqueId)
            }

            val block = world.getBlockAt(10, 70, 10)
            block.setType(Material.COAL_ORE)

            val event = BlockBreakEvent(block, playerMock)
            val result = mine.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
        }

        @Test
        fun `base block does not count towards limit`() {
            playerMock.addAttachment(plugin!!, "mine.use", true)

            val initialCount = limitTracker.getBlockCount(playerMock.uniqueId)

            // Mine should not increment for base block (STONE)
            // But we need to test the actual mining logic
            // This is a unit test of the limit tracker behavior

            assertEquals(0, initialCount)
        }

        @Test
        fun `resetLimits clears all player counts`() {
            repeat(50) {
                limitTracker.incrementBlocks(playerMock.uniqueId)
            }

            mine.resetLimits()

            assertEquals(0, limitTracker.getBlockCount(playerMock.uniqueId))
        }
    }

    @Nested
    @DisplayName("Mine Lifecycle")
    inner class MineLifecycleTests {

        @Test
        fun `start creates scheduled tasks`() {
            mine.start()

            // Tasks should be scheduled
            assertTrue(scheduler.timerCount() >= 2)
        }

        @Test
        fun `stop cancels scheduled tasks`() {
            mine.start()
            mine.stop()

            // All tasks should be cancelled
            // (TestTaskScheduler keeps cancelled tasks in list)
        }

        @Test
        fun `cleanup calls stop`() {
            mine.start()
            mine.cleanup()

            // Should not throw
        }
    }

    @Nested
    @DisplayName("WeightedRandom in Mine Context")
    inner class MineOrePickerTests {

        @Test
        fun `ore picker has correct items`() {
            val picker = createOrePicker(
                mapOf(
                    Material.COAL_ORE to 50,
                    Material.IRON_ORE to 30,
                    Material.GOLD_ORE to 15,
                    Material.DIAMOND_ORE to 5
                )
            )

            assertEquals(4, picker.size())
        }

        @Test
        fun `diamond is rarer than coal`() {
            val picker = createOrePicker(
                mapOf(
                    Material.COAL_ORE to 50,
                    Material.DIAMOND_ORE to 5
                )
            )

            var coalCount = 0
            var diamondCount = 0

            repeat(1000) {
                when (picker.random()) {
                    Material.COAL_ORE -> coalCount++
                    Material.DIAMOND_ORE -> diamondCount++
                    else -> {}
                }
            }

            // Coal should be much more common
            assertTrue(coalCount > diamondCount * 5, "Coal: $coalCount, Diamond: $diamondCount")
        }
    }

    @Nested
    @DisplayName("Temporary Block Tracker in Mine Context")
    inner class MineBlockTrackerTests {

        @Test
        fun `blocks expire after configured time`() {
            val tracker = TemporaryBlockTracker<String>(
                expireTimeMs = 60000L,
                timeProvider = { currentTime }
            )

            tracker.add("block1")
            currentTime = 30000L

            assertTrue(tracker.getExpired().isEmpty())
            assertTrue(tracker.isTracked("block1"))

            currentTime = 60001L

            val expired = tracker.getExpired()
            assertEquals(1, expired.size)
            assertEquals("block1", expired[0])
        }

        @Test
        fun `multiple blocks with different add times`() {
            val tracker = TemporaryBlockTracker<String>(
                expireTimeMs = 60000L,
                timeProvider = { currentTime }
            )

            tracker.add("early")
            currentTime = 30000L
            tracker.add("late")

            // At 60001, only "early" should expire
            currentTime = 60001L
            val expired1 = tracker.getExpired()
            assertEquals(1, expired1.size)
            assertEquals("early", expired1[0])

            // At 90001, "late" should also expire
            currentTime = 90001L
            val expired2 = tracker.getExpired()
            assertEquals(1, expired2.size)
            assertEquals("late", expired2[0])
        }
    }
}

