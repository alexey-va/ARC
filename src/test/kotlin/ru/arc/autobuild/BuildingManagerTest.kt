@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase

/**
 * Tests for BuildingManager methods that require MockBukkit.
 * Pure utility tests are in BuildingManagerUtilTest.
 */
class BuildingManagerTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
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
}
