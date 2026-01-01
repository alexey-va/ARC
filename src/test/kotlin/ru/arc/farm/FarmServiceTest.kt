@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.farm

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.event.block.BlockBreakEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.TestBase
import ru.arc.core.TestTaskScheduler

/**
 * Tests for FarmService and FarmZone implementations.
 */
class FarmServiceTest : TestBase() {

    private lateinit var playerMock: PlayerMock
    private lateinit var scheduler: TestTaskScheduler
    private lateinit var regionFactory: TestRegionFactory

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        playerMock = server.addPlayer("FarmTester")
        scheduler = TestTaskScheduler()
        regionFactory = TestRegionFactory { worldName ->
            server.worlds.find { it.name == worldName }
        }
    }

    @Nested
    @DisplayName("FarmService Lifecycle")
    inner class FarmServiceLifecycleTests {

        @Test
        fun `start initializes zones from config`() {
            val config = FarmModuleConfig(
                farms = listOf(createFarmConfig("farm1")),
                lumbermills = listOf(createLumbermillConfig("lumber1")),
                mines = listOf(createMineConfig("mine1", priority = 1))
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            assertEquals(3, service.getZones().size)
            assertNotNull(service.getZone("farm1"))
            assertNotNull(service.getZone("lumber1"))
            assertNotNull(service.getZone("mine1"))
        }

        @Test
        fun `stop clears all zones`() {
            val config = FarmModuleConfig(
                farms = listOf(createFarmConfig("farm1"))
            )
            registerTestRegions()

            val service = createService(config)
            service.start()
            assertEquals(1, service.getZones().size)

            service.stop()
            assertTrue(service.getZones().isEmpty())
        }

        @Test
        fun `zones are sorted by priority`() {
            val config = FarmModuleConfig(
                mines = listOf(
                    createMineConfig("low", priority = 1),
                    createMineConfig("high", priority = 10),
                    createMineConfig("medium", priority = 5)
                )
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            val zones = service.getZones()
            assertEquals("high", zones[0].id)
            assertEquals("medium", zones[1].id)
            assertEquals("low", zones[2].id)
        }

        @Test
        fun `resetAllLimits resets all zone limits`() {
            val config = FarmModuleConfig(
                farms = listOf(createFarmConfig("farm1"))
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            service.resetAllLimits()
            // Limits should be reset (no exception = success)
        }

        @Test
        fun `multiple farms can be loaded`() {
            val config = FarmModuleConfig(
                farms = listOf(
                    createFarmConfig("farm1"),
                    createFarmConfig("farm2")
                )
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            assertEquals(2, service.getZones().size)
            assertNotNull(service.getZone("farm1"))
            assertNotNull(service.getZone("farm2"))
        }

        @Test
        fun `multiple lumbermills can be loaded`() {
            val config = FarmModuleConfig(
                lumbermills = listOf(
                    createLumbermillConfig("lumber1"),
                    createLumbermillConfig("lumber2")
                )
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            assertEquals(2, service.getZones().size)
            assertNotNull(service.getZone("lumber1"))
            assertNotNull(service.getZone("lumber2"))
        }
    }

    @Nested
    @DisplayName("CropFarm Zone")
    inner class CropFarmZoneTests {

        private lateinit var farm: CropFarm
        private lateinit var world: World
        private val limitTracker = BlockLimitTracker(100, 10)

        @BeforeEach
        fun setUp() {
            world = server.addSimpleWorld("farm_world")
            regionFactory.registerRegion("farm_region", RegionBounds(0, 60, 0, 10, 70, 10))

            val config = FarmZoneConfig(
                id = "test_farm",
                worldName = "farm_world",
                regionName = "farm_region",
                permission = "farm.use",
                particles = false,
                maxBlocksPerDay = 100,
                priority = 0,
                blocks = setOf(Material.WHEAT, Material.CARROTS, Material.POTATOES),
                seeds = setOf(Material.WHEAT_SEEDS)
            )

            farm = CropFarm(
                id = "test_farm",
                priority = 0,
                config = config,
                region = regionFactory.create("farm_world", "farm_region"),
                adminPermission = "arc.farm-admin",
                limitTracker = limitTracker
            )
        }

        @Test
        fun `returns NotHandled for block outside region`() {
            val block = world.getBlockAt(100, 65, 100) // Outside region
            block.setType(Material.WHEAT)

            val event = BlockBreakEvent(block, playerMock)
            val result = farm.processBreak(event)

            assertEquals(BreakResult.NotHandled, result)
        }

        @Test
        fun `admin bypasses all checks`() {
            playerMock.addAttachment(plugin!!, "arc.farm-admin", true)

            val block = world.getBlockAt(5, 65, 5) // Inside region
            block.setType(Material.WHEAT)

            val event = BlockBreakEvent(block, playerMock)
            val result = farm.processBreak(event)

            assertEquals(BreakResult.Allowed, result)
            assertFalse(event.isCancelled)
        }

        @Test
        fun `denies access without permission`() {
            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.WHEAT)

            val event = BlockBreakEvent(block, playerMock)
            val result = farm.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
            assertTrue(event.isCancelled)
        }

        @Test
        fun `denies access for non-farm material`() {
            playerMock.addAttachment(plugin!!, "farm.use", true)

            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.STONE) // Not a farm block

            val event = BlockBreakEvent(block, playerMock)
            val result = farm.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
        }

        @Test
        fun `respects daily limit`() {
            playerMock.addAttachment(plugin!!, "farm.use", true)

            // Fill up the limit
            repeat(100) {
                limitTracker.incrementBlocks(playerMock.uniqueId)
            }

            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.WHEAT)

            val event = BlockBreakEvent(block, playerMock)
            val result = farm.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
        }

        @Test
        fun `resetLimits clears the tracker`() {
            repeat(50) {
                limitTracker.incrementBlocks(playerMock.uniqueId)
            }

            farm.resetLimits()

            assertEquals(0, limitTracker.getBlockCount(playerMock.uniqueId))
        }
    }

    @Nested
    @DisplayName("Lumbermill Zone")
    inner class LumbermillZoneTests {

        private lateinit var lumbermill: Lumbermill
        private lateinit var world: World

        @BeforeEach
        fun setUp() {
            world = server.addSimpleWorld("lumber_world")
            regionFactory.registerRegion("lumber_region", RegionBounds(0, 60, 0, 10, 80, 10))

            val config = LumbermillConfig(
                id = "test_lumber",
                worldName = "lumber_world",
                regionName = "lumber_region",
                permission = "lumber.use",
                particles = false,
                priority = 0,
                blocks = setOf(Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG)
            )

            lumbermill = Lumbermill(
                id = "test_lumber",
                priority = 0,
                config = config,
                region = regionFactory.create("lumber_world", "lumber_region"),
                adminPermission = "arc.farm-admin"
            )
        }

        @Test
        fun `allows breaking logs with permission`() {
            playerMock.addAttachment(plugin!!, "lumber.use", true)

            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.OAK_LOG)

            val event = BlockBreakEvent(block, playerMock)
            val result = lumbermill.processBreak(event)

            assertEquals(BreakResult.Allowed, result)
            assertFalse(event.isCancelled)
        }

        @Test
        fun `denies non-log materials`() {
            playerMock.addAttachment(plugin!!, "lumber.use", true)

            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.STONE)

            val event = BlockBreakEvent(block, playerMock)
            val result = lumbermill.processBreak(event)

            assertEquals(BreakResult.Cancelled, result)
        }

        @Test
        fun `has no limits to reset`() {
            // Should not throw
            lumbermill.resetLimits()
        }
    }

    @Nested
    @DisplayName("FarmService Event Routing")
    inner class FarmServiceEventRoutingTests {

        @Test
        fun `routes event to first matching zone`() {
            registerTestRegions()
            val config = FarmModuleConfig(
                farms = listOf(createFarmConfig("farm1")),
                lumbermills = listOf(createLumbermillConfig("lumber1"))
            )

            val service = createService(config)
            service.start()

            // Use the world created by createFarmConfig()
            val world = server.getWorld("farm_world")!!
            val block = world.getBlockAt(5, 65, 5)
            block.setType(Material.WHEAT)

            val event = BlockBreakEvent(block, playerMock)
            service.processEvent(event)

            // Event should be processed (cancelled because no permission)
            assertTrue(event.isCancelled)
        }

        @Test
        fun `ignores events not in any zone`() {
            val config = FarmModuleConfig(
                farms = listOf(createFarmConfig("farm1"))
            )
            registerTestRegions()

            val service = createService(config)
            service.start()

            val world = server.addSimpleWorld("other_world")
            val block = world.getBlockAt(100, 100, 100)
            block.setType(Material.STONE)

            val event = BlockBreakEvent(block, playerMock)
            service.processEvent(event)

            // Event should not be modified
            assertFalse(event.isCancelled)
        }
    }

    @Nested
    @DisplayName("RegionBounds")
    inner class RegionBoundsExtendedTests {

        @Test
        fun `negative coordinates work correctly`() {
            val bounds = RegionBounds(-10, 0, -10, 10, 100, 10)

            assertTrue(bounds.contains(0, 50, 0))
            assertTrue(bounds.contains(-10, 0, -10))
            assertTrue(bounds.contains(-5, 50, -5))
            assertFalse(bounds.contains(-11, 50, 0))
        }

        @Test
        fun `volume calculation with negative coords`() {
            val bounds = RegionBounds(-5, 0, -5, 5, 10, 5)

            // 11 * 11 * 11 = 1331
            assertEquals(1331L, bounds.volume())
        }
    }

    // Helper methods

    private fun createService(config: FarmModuleConfig): FarmService {
        return FarmService(
            config = config,
            regionFactory = regionFactory,
            scheduler = scheduler,
            plugin = plugin!!
        )
    }

    private fun registerTestRegions() {
        regionFactory.registerRegion("farm_region", RegionBounds(0, 60, 0, 10, 70, 10))
        regionFactory.registerRegion("lumber_region", RegionBounds(0, 60, 0, 10, 80, 10))
        // Use Y >= 64 to avoid MockBukkit min height issues
        regionFactory.registerRegion("mine_region", RegionBounds(0, 64, 0, 10, 80, 10))
    }

    private fun createFarmConfig(id: String = "farm1"): FarmZoneConfig {
        if (server.getWorld("farm_world") == null) {
            server.addSimpleWorld("farm_world")
        }
        return FarmZoneConfig(
            id = id,
            worldName = "farm_world",
            regionName = "farm_region",
            permission = "farm.use",
            particles = false,
            maxBlocksPerDay = 256,
            priority = 0,
            blocks = setOf(Material.WHEAT, Material.CARROTS),
            seeds = setOf(Material.WHEAT_SEEDS)
        )
    }

    private fun createLumbermillConfig(id: String = "lumber1"): LumbermillConfig {
        if (server.getWorld("lumber_world") == null) {
            server.addSimpleWorld("lumber_world")
        }
        return LumbermillConfig(
            id = id,
            worldName = "lumber_world",
            regionName = "lumber_region",
            permission = "lumber.use",
            particles = false,
            priority = 0,
            blocks = setOf(Material.OAK_LOG, Material.BIRCH_LOG)
        )
    }

    private fun createMineConfig(id: String, priority: Int = 1): MineConfig {
        server.addSimpleWorld("mine_world")
        return MineConfig(
            id = id,
            worldName = "mine_world",
            regionName = "mine_region",
            permission = "mine.use",
            particles = false,
            maxBlocksPerDay = 256,
            priority = priority,
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
    }
}

