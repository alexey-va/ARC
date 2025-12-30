@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.common.locationpools

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

class LocationPoolTest : TestBase() {

    private lateinit var world: WorldMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
    }

    @Test
    fun testCreatePool() {
        val pool = LocationPool("test-pool")
        assertEquals("test-pool", pool.id, "Pool ID should match")
        assertTrue(pool.locations.values().isEmpty(), "Pool should start empty")
    }

    @Test
    fun testAddLocation() {
        val pool = LocationPool("test-pool")
        val location = Location(world, 10.0, 20.0, 30.0)
        val weight = 1.5

        pool.addLocation(location, weight)

        assertEquals(1, pool.locations.size(), "Pool should have 1 location")
        assertTrue(pool.isDirty, "Pool should be marked as dirty")
    }

    @Test
    fun testAddLocationWithWeight() {
        val pool = LocationPool("test-pool")
        val location1 = Location(world, 10.0, 20.0, 30.0)
        val location2 = Location(world, 20.0, 30.0, 40.0)

        pool.addLocation(location1, 1.0)
        pool.addLocation(location2, 2.0)

        assertEquals(2, pool.locations.size(), "Pool should have 2 locations")
    }

    @Test
    fun testRemoveLocation() {
        val pool = LocationPool("test-pool")
        val location = Location(world, 10.0, 20.0, 30.0)

        pool.addLocation(location, 1.0)
        val removed = pool.removeLocation(location)

        assertTrue(removed, "Location should be removed")
        assertEquals(0, pool.locations.size(), "Pool should be empty after removal")
    }

    @Test
    fun testRemoveNonExistentLocation() {
        val pool = LocationPool("test-pool")
        val location = Location(world, 10.0, 20.0, 30.0)

        val removed = pool.removeLocation(location)

        assertFalse(removed, "Should return false for non-existent location")
    }

    @Test
    fun testGetNRandom() {
        val pool = LocationPool("test-pool")
        pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)
        pool.addLocation(Location(world, 20.0, 30.0, 40.0), 1.0)
        pool.addLocation(Location(world, 30.0, 40.0, 50.0), 1.0)

        val random = pool.getNRandom(2)

        assertEquals(2, random.size, "Should return 2 random locations")
    }

    @Test
    fun testGetNRandomMoreThanAvailable() {
        val pool = LocationPool("test-pool")
        pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)

        val random = pool.getNRandom(5)

        assertTrue(random.size <= 1, "Should return at most 1 location")
    }

    @Test
    fun testNearbyLocations() {
        val pool = LocationPool("test-pool")
        val location1 = Location(world, 0.0, 0.0, 0.0)
        val location2 = Location(world, 100.0, 0.0, 0.0)
        val location3 = Location(world, 0.0, 0.0, 10.0)

        pool.addLocation(location1, 1.0)
        pool.addLocation(location2, 1.0)
        pool.addLocation(location3, 1.0)

        val center = Location(world, 0.0, 0.0, 5.0)
        val nearby = pool.nearbyLocations(center, 50.0)

        // location1 and location3 should be nearby, location2 should not
        assertEquals(2, nearby.size, "Should find 2 nearby locations")
    }

    @Test
    fun testNearbyLocationsEmpty() {
        val pool = LocationPool("test-pool")
        val center = Location(world, 0.0, 0.0, 0.0)

        val nearby = pool.nearbyLocations(center, 50.0)

        assertTrue(nearby.isEmpty(), "Should return empty set for empty pool")
    }

    @Test
    fun testDirtyFlag() {
        val pool = LocationPool("test-pool")
        assertFalse(pool.isDirty, "Pool should start clean")

        pool.addLocation(Location(world, 10.0, 20.0, 30.0), 1.0)
        assertTrue(pool.isDirty, "Pool should be dirty after adding location")
    }
}

