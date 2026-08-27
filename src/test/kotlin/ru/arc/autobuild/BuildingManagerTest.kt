
package ru.arc.autobuild

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler

/**
 * Tests for BuildingManager methods that require MockBukkit.
 * Pure utility tests are in BuildingManagerUtilTest.
 */
class BuildingManagerTest : TestBase() {
    private lateinit var world: WorldMock
    private lateinit var player: PlayerMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("builder-manager-world")
        player = server.addPlayer("BuilderManagerPlayer")
    }

    // ========== Building Map Tests ==========

    @Test
    fun testAddBuilding() {
        val building = Building("test.schem")
        BuildingManager.addBuilding(building)

        assertEquals(building, BuildingManager.getBuilding("test.schem"), "Building should be retrievable")
    }

    @Test
    fun testGetBuildingNotFound() {
        val result = BuildingManager.getBuilding("nonexistent.schem")

        assertNull(result, "Should return null for non-existent building")
    }

    @Test
    fun testGetBuildings() {
        val initialCount = BuildingManager.getBuildings().size

        val building1 = Building("testgb1.schem")
        val building2 = Building("testgb2.schem")
        val building3 = Building("testgb3.schem")

        BuildingManager.addBuilding(building1)
        BuildingManager.addBuilding(building2)
        BuildingManager.addBuilding(building3)

        val buildings = BuildingManager.getBuildings()

        assertEquals(initialCount + 3, buildings.size, "Should have added 3 buildings")
        assertTrue(buildings.contains(building1))
        assertTrue(buildings.contains(building2))
        assertTrue(buildings.contains(building3))
    }

    @Test
    fun testAddBuildingOverwrite() {
        val building1 = Building("testow.schem")
        val building2 = Building("testow.schem")

        BuildingManager.addBuilding(building1)
        BuildingManager.addBuilding(building2)

        val result = BuildingManager.getBuilding("testow.schem")

        assertSame(building2, result, "Second building should overwrite first")
    }

    // ========== Pending Construction Tests ==========

    @Test
    fun testGetPendingConstructionNotFound() {
        val result = BuildingManager.getPendingConstruction(java.util.UUID.randomUUID())

        assertNull(result, "Should return null when no pending construction")
    }

    @Test
    fun `reload removes schematics deleted from disk`() {
        createMockSchematic(plugin.dataFolder, "temporary.schem")
        BuildingManager.init()
        assertTrue(BuildingManager.getBuilding("temporary.schem") != null)

        plugin.dataFolder.resolve("schematics/temporary.schem").delete()
        BuildingManager.init()

        assertNull(BuildingManager.getBuilding("temporary.schem"))
    }

    @Test
    fun `shutdown cancels an active construction without synthesizing completion`() {
        val (site, construction) = controlledActiveSite()
        val buildChunk = site.adjustedCenter.chunk

        assertTrue(buildChunk.isForceLoaded, "Active construction should keep its chunks loaded")
        while (player.nextMessage() != null) {
            // Drain setup feedback so shutdown silence is asserted independently.
        }
        while (player.nextComponentMessage() != null) {
            // Drain setup feedback so shutdown silence is asserted independently.
        }

        BuildingManager.stopAll()

        assertEquals(ConstructionState.Cancelled, site.state)
        assertEquals(-1, construction.pointer.get(), "Shutdown must not place the remaining schematic blocks")
        assertFalse(buildChunk.isForceLoaded, "Shutdown should release construction chunk loading")
        assertNull(player.nextMessage(), "Shutdown cancellation should be silent")
        assertNull(player.nextComponentMessage(), "Shutdown cancellation should be silent")
    }

    @Test
    fun `stale cleanup keeps an active construction managed`() {
        val (site, _) = controlledActiveSite()
        site.timestamp = System.currentTimeMillis() - 181_000

        server.scheduler.performTicks(20)

        assertSame(site, BuildingManager.findByNpcId(site.npcId))
        assertEquals(ConstructionState.Building, site.state)
    }

    private fun controlledActiveSite(): Pair<ConstructionSite, Construction> {
        val building = Building("amogus_1.schem")
        val site = ConstructionSite(
            building,
            Location(world, 0.0, 64.0, 0.0),
            player,
            0,
            world,
            0,
            0,
        )
        assertTrue(site.startDisplayingBorder())
        assertTrue(site.startConfirmation())

        val construction = Construction(
            site,
            LifecycleTaskScope(TestTaskScheduler()),
        ) { emptyList() }
        site.construction = construction
        BuildingManager.startConstruction(site)
        assertEquals(ConstructionState.Building, site.state)
        return site to construction
    }
}
