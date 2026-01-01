@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

/**
 * Comprehensive tests for the construction flow lifecycle.
 * Tests all state transitions and edge cases.
 */
class ConstructionFlowTest : TestBase() {

    private lateinit var world: WorldMock
    private lateinit var world2: WorldMock
    private lateinit var player: PlayerMock
    private lateinit var building: Building
    private lateinit var centerBlock: Location

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
        world2 = server.addSimpleWorld("other-world")
        player = server.addPlayer("TestPlayer")
        centerBlock = Location(world, 100.0, 64.0, 100.0)
        building = Building("amogus_1.schem")
    }

    // ==================== State Lifecycle Tests ====================

    @Nested
    @DisplayName("Full Lifecycle Flow")
    inner class LifecycleTests {

        @Test
        @DisplayName("Created -> DisplayingOutline transition")
        fun testCreatedToDisplayingOutline() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            assertEquals(ConstructionState.Created, site.state)

            assertTrue(site.startDisplayingBorder())
            assertEquals(ConstructionState.DisplayingOutline, site.state)
            assertNotNull(site.display, "Display should be created")
        }

        @Test
        @DisplayName("DisplayingOutline -> Confirmation transition")
        fun testDisplayingOutlineToConfirmation() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()

            assertTrue(site.startConfirmation())
            assertEquals(ConstructionState.Confirmation, site.state)
            assertNotNull(site.construction, "Construction should be created")
            assertTrue(site.npcId >= 0 || site.npcId == -1, "NPC ID should be set or -1 if citizens not available")
        }

        @Test
        @DisplayName("Confirmation -> Building transition")
        fun testConfirmationToBuilding() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()

            assertTrue(site.startBuild())
            assertEquals(ConstructionState.Building, site.state)
        }

        @Test
        @DisplayName("Building -> Done transition")
        fun testBuildingToDone() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()
            site.startBuild()

            assertTrue(site.complete())
            assertEquals(ConstructionState.Done, site.state)
        }

        @Test
        @DisplayName("Full happy path: Created -> DisplayingOutline -> Confirmation -> Building -> Done")
        fun testFullHappyPath() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            // Step 1: Start outline
            assertEquals(ConstructionState.Created, site.state)
            assertTrue(site.startDisplayingBorder())
            assertEquals(ConstructionState.DisplayingOutline, site.state)

            // Step 2: Confirm (spawn NPC)
            assertTrue(site.startConfirmation())
            assertEquals(ConstructionState.Confirmation, site.state)

            // Step 3: Start building
            assertTrue(site.startBuild())
            assertEquals(ConstructionState.Building, site.state)

            // Step 4: Complete
            assertTrue(site.complete())
            assertEquals(ConstructionState.Done, site.state)
        }
    }

    // ==================== Cancellation Tests ====================

    @Nested
    @DisplayName("Cancellation Scenarios")
    inner class CancellationTests {

        @Test
        @DisplayName("Cancel from Created state")
        fun testCancelFromCreated() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            assertTrue(site.cancel())
            assertEquals(ConstructionState.Cancelled, site.state)
        }

        @Test
        @DisplayName("Cancel from DisplayingOutline state")
        fun testCancelFromDisplayingOutline() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()

            assertTrue(site.cancel())
            assertEquals(ConstructionState.Cancelled, site.state)
        }

        @Test
        @DisplayName("Cancel from Confirmation state")
        fun testCancelFromConfirmation() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()

            assertTrue(site.cancel())
            assertEquals(ConstructionState.Cancelled, site.state)
        }

        @Test
        @DisplayName("Cancel from Building state")
        fun testCancelFromBuilding() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()
            site.startBuild()

            assertTrue(site.cancel())
            assertEquals(ConstructionState.Cancelled, site.state)
        }

        @Test
        @DisplayName("Cannot cancel from Done state")
        fun testCannotCancelFromDone() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()
            site.startBuild()
            site.complete()

            assertFalse(site.cancel(), "Should not be able to cancel from Done state")
            assertEquals(ConstructionState.Done, site.state)
        }

        @Test
        @DisplayName("Cannot cancel twice")
        fun testCannotCancelTwice() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.cancel()

            assertFalse(site.cancel(), "Should not be able to cancel from Cancelled state")
            assertEquals(ConstructionState.Cancelled, site.state)
        }
    }

    // ==================== Invalid Transition Tests ====================

    @Nested
    @DisplayName("Invalid State Transitions")
    inner class InvalidTransitionTests {

        @Test
        @DisplayName("Cannot skip DisplayingOutline")
        fun testCannotSkipDisplayingOutline() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            assertFalse(site.startConfirmation(), "Cannot go directly from Created to Confirmation")
            assertEquals(ConstructionState.Created, site.state)
        }

        @Test
        @DisplayName("Cannot skip Confirmation")
        fun testCannotSkipConfirmation() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()

            assertFalse(site.startBuild(), "Cannot go directly from DisplayingOutline to Building")
            assertEquals(ConstructionState.DisplayingOutline, site.state)
        }

        @Test
        @DisplayName("Cannot skip Building")
        fun testCannotSkipBuilding() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()

            assertFalse(site.complete(), "Cannot go directly from Confirmation to Done")
            assertEquals(ConstructionState.Confirmation, site.state)
        }

        @Test
        @DisplayName("Cannot go back to DisplayingOutline")
        fun testCannotGoBackToDisplayingOutline() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()

            assertFalse(site.startDisplayingBorder(), "Cannot go back to DisplayingOutline")
            assertEquals(ConstructionState.Confirmation, site.state)
        }

        @Test
        @DisplayName("Cannot transition from Done to anything")
        fun testCannotTransitionFromDone() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()
            site.startBuild()
            site.complete()

            assertFalse(site.startDisplayingBorder())
            assertFalse(site.startConfirmation())
            assertFalse(site.startBuild())
            assertFalse(site.cancel())
            assertEquals(ConstructionState.Done, site.state)
        }

        @Test
        @DisplayName("Cannot transition from Cancelled to anything")
        fun testCannotTransitionFromCancelled() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.cancel()

            assertFalse(site.startDisplayingBorder())
            assertFalse(site.startConfirmation())
            assertFalse(site.startBuild())
            assertFalse(site.complete())
            assertEquals(ConstructionState.Cancelled, site.state)
        }
    }

    // ==================== finishInstantly Tests ====================

    @Nested
    @DisplayName("Instant Finish (Admin Command)")
    inner class FinishInstantlyTests {

        @Test
        @DisplayName("finishInstantly returns false from Created state")
        fun testFinishInstantlyFromCreated() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            val result = site.finishInstantly()

            assertFalse(result)
            assertEquals(ConstructionState.Created, site.state)
        }

        @Test
        @DisplayName("finishInstantly returns false from DisplayingOutline state")
        fun testFinishInstantlyFromDisplayingOutline() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()

            val result = site.finishInstantly()

            assertFalse(result)
            assertEquals(ConstructionState.DisplayingOutline, site.state)
        }

        @Test
        @DisplayName("finishInstantly returns false from Confirmation state")
        fun testFinishInstantlyFromConfirmation() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()

            val result = site.finishInstantly()

            assertFalse(result)
            assertEquals(ConstructionState.Confirmation, site.state)
        }

        @Test
        @DisplayName("finishInstantly only works from Building state")
        fun testFinishInstantlyRequiresBuildingState() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            // None of these states should allow finishInstantly
            assertFalse(site.finishInstantly())

            site.startDisplayingBorder()
            assertFalse(site.finishInstantly())

            site.startConfirmation()
            assertFalse(site.finishInstantly())

            // Only Building state would work, but that requires complex setup
            // which triggers initialization errors in test environment
        }
    }

    // ==================== same() Method Tests ====================

    @Nested
    @DisplayName("same() Method - Location/Building Matching")
    inner class SameMethodTests {

        @Test
        @DisplayName("same() returns true for matching parameters")
        fun testSameMatchingParameters() {
            player.setRotation(180f, 0f) // yaw 180 -> rotation 0
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            assertTrue(site.same(player, centerBlock, building))
        }

        @Test
        @DisplayName("same() uses fileName not object reference")
        fun testSameUsesFileName() {
            player.setRotation(180f, 0f)
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            val sameBuilding = Building("amogus_1.schem")
            assertTrue(site.same(player, centerBlock, sameBuilding))
        }

        @Test
        @DisplayName("same() returns false for different building")
        fun testSameDifferentBuilding() {
            player.setRotation(180f, 0f)
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            createMockSchematic(plugin.dataFolder, "different.schem")
            val differentBuilding = Building("different.schem")

            assertFalse(site.same(player, centerBlock, differentBuilding))
        }

        @Test
        @DisplayName("same() returns false for different location")
        fun testSameDifferentLocation() {
            player.setRotation(180f, 0f)
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            val differentLocation = Location(world, 200.0, 64.0, 200.0)
            assertFalse(site.same(player, differentLocation, building))
        }

        @Test
        @DisplayName("same() returns false for different rotation")
        fun testSameDifferentRotation() {
            player.setRotation(180f, 0f) // rotation 0
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

            player.setRotation(90f, 0f) // rotation 90
            assertFalse(site.same(player, centerBlock, building))
        }
    }

    // ==================== Progress Tests ====================

    @Nested
    @DisplayName("Building Progress")
    inner class ProgressTests {

        @Test
        @DisplayName("Progress is 0 before building starts")
        fun testProgressZeroBeforeBuilding() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            assertEquals(0.0, site.progress, 0.001)

            site.startDisplayingBorder()
            assertEquals(0.0, site.progress, 0.001)

            site.startConfirmation()
            assertEquals(0.0, site.progress, 0.001)
        }

        @Test
        @DisplayName("Progress is 0 at start of building (no blocks placed yet)")
        fun testProgressAtStartOfBuilding() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            site.startDisplayingBorder()
            site.startConfirmation()
            site.startBuild()

            // Progress starts at 0 (pointer is 0, no blocks placed yet)
            assertEquals(0.0, site.progress, 0.001)
        }
    }

    // ==================== Rotation Tests ====================

    @Nested
    @DisplayName("Rotation Calculations")
    inner class RotationTests {

        @Test
        @DisplayName("fullRotation combines rotation and subRotation")
        fun testFullRotation() {
            val site = ConstructionSite(building, centerBlock, player, 90, world, 45, 0)
            assertEquals(135, site.fullRotation)
        }

        @Test
        @DisplayName("fullRotation wraps at 360")
        fun testFullRotationWraps() {
            val site = ConstructionSite(building, centerBlock, player, 270, world, 180, 0)
            assertEquals(90, site.fullRotation) // 450 % 360 = 90
        }

        @Test
        @DisplayName("rotationFromYaw returns correct values")
        fun testRotationFromYaw() {
            // North (yaw ~180) -> 0
            assertEquals(0, BuildingManager.rotationFromYaw(-135f))
            // East (yaw ~-90) -> 90  
            assertEquals(90, BuildingManager.rotationFromYaw(-45f))
            // South (yaw ~0) -> 180
            assertEquals(180, BuildingManager.rotationFromYaw(45f))
            // West (yaw ~90) -> 270
            assertEquals(270, BuildingManager.rotationFromYaw(135f))
        }
    }

    // ==================== Corner & Offset Tests ====================

    @Nested
    @DisplayName("Corners and Offsets")
    inner class CornersTests {

        @Test
        @DisplayName("Corners are properly ordered (min/max)")
        fun testCornersOrdered() {
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            val corners = site.corners

            assertTrue(corners.corner1.x() <= corners.corner2.x())
            assertTrue(corners.corner1.y() <= corners.corner2.y())
            assertTrue(corners.corner1.z() <= corners.corner2.z())
        }

        @Test
        @DisplayName("adjustedCenter applies yOffset")
        fun testAdjustedCenter() {
            val yOffset = 10
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, yOffset)

            val adjusted = site.adjustedCenter
            assertEquals(centerBlock.x, adjusted.x, 0.001)
            assertEquals(centerBlock.y + yOffset, adjusted.y, 0.001)
            assertEquals(centerBlock.z, adjusted.z, 0.001)
        }

        @Test
        @DisplayName("Corners Y is affected by yOffset")
        fun testCornersWithYOffset() {
            val yOffset = 5
            val siteNoOffset = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)
            val siteWithOffset = ConstructionSite(building, centerBlock, player, 0, world, 0, yOffset)

            assertEquals(
                siteNoOffset.corners.corner1.y() + yOffset,
                siteWithOffset.corners.corner1.y()
            )
        }

        @Test
        @DisplayName("Negative yOffset works correctly")
        fun testNegativeYOffset() {
            val yOffset = -5
            val site = ConstructionSite(building, centerBlock, player, 0, world, 0, yOffset)

            assertEquals(centerBlock.y + yOffset, site.adjustedCenter.y, 0.001)
        }
    }

    // ==================== Building Object Tests ====================

    @Nested
    @DisplayName("Building Object")
    inner class BuildingTests {

        @Test
        fun testBuildingFileName() {
            assertEquals("amogus_1.schem", building.fileName)
        }

        @Test
        fun testBuildingToString() {
            assertEquals("Building(amogus_1.schem)", building.toString())
        }

        @Test
        fun testBuildingVolumeIsPositive() {
            assertTrue(building.volume > 0)
        }

        @Test
        fun testBuildingCornersExist() {
            assertNotNull(building.getCorner1(0))
            assertNotNull(building.getCorner2(0))
        }

        @Test
        fun testBuildingRotations() {
            // All rotations should work without exceptions
            listOf(0, 90, 180, 270, 360).forEach { rotation ->
                assertNotNull(building.getCorner1(rotation))
                assertNotNull(building.getCorner2(rotation))
            }
        }
    }

    // ==================== toString Test ====================

    @Test
    @DisplayName("ConstructionSite toString includes state")
    fun testToStringIncludesState() {
        val site = ConstructionSite(building, centerBlock, player, 0, world, 0, 0)

        assertTrue(site.toString().contains("Created"))

        site.startDisplayingBorder()
        assertTrue(site.toString().contains("DisplayingOutline"))
    }
}

