@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.TestBase

class BuildingTest : TestBase() {

    private lateinit var building: Building

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        assertNotNull(plugin, "Plugin must be loaded for tests")
        building = Building("amogus_1.schem")
    }

    @Test
    fun testBuildingCreation() {
        assertNotNull(building, "Building should be created")
        assertEquals("amogus_1.schem", building.fileName, "Filename should match")
    }

    @Test
    fun testGetFileName() {
        assertEquals("amogus_1.schem", building.fileName)
    }

    @Test
    fun testMultipleBuildingsWithDifferentNames() {
        val building1 = Building("test1.schem")
        val building2 = Building("test2.schem")
        val building3 = Building("test3.schem")

        assertNotEquals(building1.fileName, building2.fileName)
        assertNotEquals(building2.fileName, building3.fileName)
        assertNotEquals(building1.fileName, building3.fileName)
    }

    @Test
    fun testLoadClipboardNonExistentFile() {
        val badBuilding = Building("non_existent.schem")

        assertThrows(IllegalArgumentException::class.java) {
            badBuilding.loadClipboard()
        }
    }

    @Test
    fun testLoadClipboard() {
        assertDoesNotThrow { building.loadClipboard() }
    }

    @Test
    fun testVolumeAfterLoad() {
        building.loadClipboard()
        val volume = building.volume
        assertTrue(volume > 0, "Volume should be positive")
    }

    @Test
    fun testBuildingEquals() {
        val building1 = Building("test.schem")
        val building2 = Building("test.schem")

        // Different objects even with same filename
        assertNotSame(building1, building2)
    }

    @Test
    fun testFileNameNotEmpty() {
        assertFalse(building.fileName.isEmpty(), "Filename should not be empty")
    }

    @Test
    fun testFileNameEndsWithSchem() {
        assertTrue(building.fileName.endsWith(".schem"), "Filename should end with .schem")
    }
}
