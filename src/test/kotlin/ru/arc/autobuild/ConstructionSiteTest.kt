@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

class ConstructionSiteTest : TestBase() {

    private lateinit var world: WorldMock
    private lateinit var player: PlayerMock
    private lateinit var building: Building
    private lateinit var centerBlock: Location

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        assertNotNull(plugin, "Plugin must be loaded for tests")
        world = server.addSimpleWorld("test-world")
        player = server.addPlayer("TestPlayer")
        centerBlock = Location(world, 0.0, 64.0, 0.0)
        building = Building("amogus_1.schem")
    }

    // ========== Creation Tests ==========

    @Test
    fun testConstructionSiteCreation() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertNotNull(site, "Site should be created")
        assertEquals(ConstructionState.Created, site.state, "Initial state should be CREATED")
        assertSame(building, site.building, "Building should match")
        assertSame(player, site.player, "Player should match")
        assertSame(world, site.world, "World should match")
        assertEquals(0, site.rotation, "Rotation should be 0")
        assertEquals(0, site.subRotation, "SubRotation should be 0")
        assertEquals(0, site.yOffset, "YOffset should be 0")
    }

    @Test
    fun testConstructionSiteWithRotation() {

        val site = ConstructionSite(building, centerBlock, player, 90, world, 0, 0)

        assertEquals(90, site.rotation, "Rotation should be 90")
    }

    @Test
    fun testConstructionSiteWithSubRotation() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 45, 0)

        assertEquals(45, site.subRotation, "SubRotation should be 45")
    }

    @Test
    fun testConstructionSiteWithYOffset() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 10)

        assertEquals(10, site.yOffset, "YOffset should be 10")
    }

    // ========== fullRotation Tests ==========

    @Test
    fun testFullRotationZero() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertEquals(0, site.fullRotation, "0 + 0 = 0")
    }

    @Test
    fun testFullRotationSum() {

        val site = ConstructionSite(building, centerBlock, player, 90, world, 90, 0)

        assertEquals(180, site.fullRotation, "90 + 90 = 180")
    }

    @Test
    fun testFullRotationModulo() {

        val site = ConstructionSite(building, centerBlock, player, 270, world, 180, 0)

        assertEquals(90, site.fullRotation, "270 + 180 = 450 % 360 = 90")
    }

    @Test
    fun testFullRotationExact360() {

        val site = ConstructionSite(building, centerBlock, player, 180, world, 180, 0)

        assertEquals(0, site.fullRotation, "180 + 180 = 360 % 360 = 0")
    }

    // ========== adjustedCenter Tests ==========

    @Test
    fun testAdjustedCenterNoOffset() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        val center = site.adjustedCenter

        assertEquals(centerBlock.x, center.x, 0.001, "X should match")
        assertEquals(centerBlock.y, center.y, 0.001, "Y should match")
        assertEquals(centerBlock.z, center.z, 0.001, "Z should match")
    }

    @Test
    fun testAdjustedCenterWithYOffset() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 10)

        val center = site.adjustedCenter

        assertEquals(centerBlock.x, center.x, 0.001, "X should match")
        assertEquals(centerBlock.y + 10, center.y, 0.001, "Y should be offset by 10")
        assertEquals(centerBlock.z, center.z, 0.001, "Z should match")
    }

    @Test
    fun testAdjustedCenterNegativeYOffset() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, -5)

        val center = site.adjustedCenter

        assertEquals(centerBlock.y - 5, center.y, 0.001, "Y should be offset by -5")
    }

    // ========== State Tests ==========

    @Test
    fun testInitialState() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertEquals(ConstructionState.Created, site.state)
    }

    @Test
    fun testNpcIdInitial() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertEquals(-1, site.npcId, "Initial NPC ID should be -1")
    }

    @Test
    fun testDisplayInitiallyNull() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertNull(site.display, "Display should be null initially")
    }

    @Test
    fun testConstructionInitiallyNull() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertNull(site.construction, "Construction should be null initially")
    }

    // ========== State Transition Tests ==========

    @Test
    fun testStartDisplayingBorder() {
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        site.startDisplayingBorder()
        assertNotNull(site.display, "Display should be created")
        assertEquals(ConstructionState.DisplayingOutline, site.state)
    }

    @Test
    fun testStartDisplayingBorderReturnsFalseWhenAlreadyDisplaying() {
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        site.startDisplayingBorder()
        // Second call should return false (invalid transition)
        assertFalse(site.startDisplayingBorder())
    }

    @Test
    fun testCancelFromDisplayingOutline() {
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        site.startDisplayingBorder()
        site.cancel()
        assertEquals(ConstructionState.Cancelled, site.state)
    }

    @Test
    fun testCancelFromCreated() {
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        // Can cancel from Created state
        assertTrue(site.cancel())
        assertEquals(ConstructionState.Cancelled, site.state)
    }

    // ========== same() Tests ==========

    @Test
    fun testSameSameParameters() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        player.teleport(Location(world, 0.0, 64.0, 0.0, 180f, 0f)) // Yaw 180 = rotation 0

        val result = site.same(player, centerBlock, building)

        assertTrue(result, "Same parameters should return true")
    }

    @Test
    fun testSameDifferentLocation() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        val differentLocation = Location(world, 10.0, 64.0, 10.0)
        player.teleport(Location(world, 0.0, 64.0, 0.0, 180f, 0f))

        val result = site.same(player, differentLocation, building)

        assertFalse(result, "Different location should return false")
    }

    @Test
    fun testSameDifferentBuilding() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        val differentBuilding = Building("other.schem")
        player.teleport(Location(world, 0.0, 64.0, 0.0, 180f, 0f))

        val result = site.same(player, centerBlock, differentBuilding)

        assertFalse(result, "Different building should return false")
    }

    @Test
    fun testSameDifferentRotation() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        player.teleport(Location(world, 0.0, 64.0, 0.0, 0f, 0f)) // Yaw 0 = rotation 180

        val result = site.same(player, centerBlock, building)

        assertFalse(result, "Different rotation should return false")
    }

    // ========== progress Tests ==========

    @Test
    fun testProgressWhenNotBuilding() {

        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        val progress = site.progress

        assertEquals(0.0, progress, 0.001, "Progress should be 0 when not building")
    }

    // ========== Timestamp Tests ==========

    @Test
    fun testTimestampIsSet() {

        val before = System.currentTimeMillis()
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
        val after = System.currentTimeMillis()

        assertTrue(site.timestamp >= before, "Timestamp should be >= before creation")
        assertTrue(site.timestamp <= after, "Timestamp should be <= after creation")
    }
}
