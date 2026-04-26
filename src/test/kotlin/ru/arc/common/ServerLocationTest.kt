package ru.arc.common

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.TestBase

class ServerLocationTest : TestBase() {

    private lateinit var world: WorldMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        world = server.addSimpleWorld("test-world")
    }

    // ========== Of Method Tests ==========

    @Test
    fun testOfLocationWithNull() {
        // Test that of() throws IllegalArgumentException for null location
        assertThrows<IllegalArgumentException> {
            ServerLocation.of(null)
        }
    }

    @Test
    fun testOfLocation() {
        val location = Location(world, 10.5, 20.0, 30.5, 45.0f, 90.0f)
        val serverLocation = ServerLocation.of(location)

        assertNotNull(serverLocation, "ServerLocation should not be null")
        assertEquals("test-server", serverLocation.server, "Server name should match")
        assertEquals("test-world", serverLocation.world, "World name should match")
        assertEquals(10.5, serverLocation.x, 0.001, "X coordinate should match")
        assertEquals(20.0, serverLocation.y, 0.001, "Y coordinate should match")
        assertEquals(30.5, serverLocation.z, 0.001, "Z coordinate should match")
        assertEquals(45.0f, serverLocation.yaw, 0.001f, "Yaw should match")
        assertEquals(90.0f, serverLocation.pitch, 0.001f, "Pitch should match")
    }

    @Test
    fun testOfLocationWithZeroCoordinates() {
        val location = Location(world, 0.0, 0.0, 0.0, 0.0f, 0.0f)
        val serverLocation = ServerLocation.of(location)

        assertEquals(0.0, serverLocation.x, 0.001, "X should be 0")
        assertEquals(0.0, serverLocation.y, 0.001, "Y should be 0")
        assertEquals(0.0, serverLocation.z, 0.001, "Z should be 0")
        assertEquals(0.0f, serverLocation.yaw, 0.001f, "Yaw should be 0")
        assertEquals(0.0f, serverLocation.pitch, 0.001f, "Pitch should be 0")
    }

    @Test
    fun testOfLocationWithNegativeCoordinates() {
        val location = Location(world, -10.5, -20.0, -30.5, -45.0f, -90.0f)
        val serverLocation = ServerLocation.of(location)

        assertEquals(-10.5, serverLocation.x, 0.001, "X should be negative")
        assertEquals(-20.0, serverLocation.y, 0.001, "Y should be negative")
        assertEquals(-30.5, serverLocation.z, 0.001, "Z should be negative")
        assertEquals(-45.0f, serverLocation.yaw, 0.001f, "Yaw should be negative")
        assertEquals(-90.0f, serverLocation.pitch, 0.001f, "Pitch should be negative")
    }

    @Test
    fun testOfLocationWithLargeCoordinates() {
        val location = Location(world, 30000000.0, 256.0, 30000000.0, 180.0f, 90.0f)
        val serverLocation = ServerLocation.of(location)

        assertEquals(30000000.0, serverLocation.x, 0.001, "X should handle large values")
        assertEquals(256.0, serverLocation.y, 0.001, "Y should handle large values")
        assertEquals(30000000.0, serverLocation.z, 0.001, "Z should handle large values")
    }

    @Test
    fun testOfLocationWithPreciseCoordinates() {
        val location = Location(world, 123.456789, 234.567890, 345.678901, 123.456f, 234.567f)
        val serverLocation = ServerLocation.of(location)

        assertEquals(123.456789, serverLocation.x, 0.000001, "X should preserve precision")
        assertEquals(234.567890, serverLocation.y, 0.000001, "Y should preserve precision")
        assertEquals(345.678901, serverLocation.z, 0.000001, "Z should preserve precision")
    }

    @Test
    fun testOfLocationWithExtremeYawPitch() {
        val location = Location(world, 0.0, 0.0, 0.0, 360.0f, 180.0f)
        val serverLocation = ServerLocation.of(location)

        assertEquals(360.0f, serverLocation.yaw, 0.001f, "Yaw should handle 360")
        assertEquals(180.0f, serverLocation.pitch, 0.001f, "Pitch should handle 180")
    }

    // ========== ToLocation Method Tests ==========

    @Test
    fun testToLocation() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(10.5)
            .y(20.0)
            .z(30.5)
            .yaw(45.0f)
            .pitch(90.0f)
            .build()

        val location = serverLocation.toLocation()

        assertNotNull(location, "Location should not be null")
        assertEquals(world, location!!.world, "World should match")
        assertEquals(10.5, location.x, 0.001, "X coordinate should match")
        assertEquals(20.0, location.y, 0.001, "Y coordinate should match")
        assertEquals(30.5, location.z, 0.001, "Z coordinate should match")
        assertEquals(45.0f, location.yaw, 0.001f, "Yaw should match")
        assertEquals(90.0f, location.pitch, 0.001f, "Pitch should match")
    }

    @Test
    fun testToLocationWithNonExistentWorld() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("non-existent-world")
            .x(10.5)
            .y(20.0)
            .z(30.5)
            .build()

        val location = serverLocation.toLocation()

        assertNull(location, "Location should be null for non-existent world")
    }

    @Test
    fun testToLocationWithNullWorld() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world(null)
            .x(10.5)
            .y(20.0)
            .z(30.5)
            .build()

        val location = serverLocation.toLocation()

        assertNull(location, "Location should be null for null world")
    }

    @Test
    fun testToLocationWithEmptyWorldName() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("")
            .x(10.5)
            .y(20.0)
            .z(30.5)
            .build()

        val location = serverLocation.toLocation()

        assertNull(location, "Location should be null for empty world name")
    }

    @Test
    fun testToLocationWithZeroCoordinates() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = serverLocation.toLocation()

        assertNotNull(location, "Location should not be null")
        assertEquals(0.0, location!!.x, 0.001, "X should be 0")
        assertEquals(0.0, location.y, 0.001, "Y should be 0")
        assertEquals(0.0, location.z, 0.001, "Z should be 0")
    }

    @Test
    fun testToLocationWithNegativeCoordinates() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(-10.5)
            .y(-20.0)
            .z(-30.5)
            .build()

        val location = serverLocation.toLocation()

        assertNotNull(location, "Location should not be null")
        assertEquals(-10.5, location!!.x, 0.001, "X should be negative")
        assertEquals(-20.0, location.y, 0.001, "Y should be negative")
        assertEquals(-30.5, location.z, 0.001, "Z should be negative")
    }

    @Test
    fun testToLocationWithMultipleWorlds() {
        val world2 = server.addSimpleWorld("test-world-2")

        val serverLocation1 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(10.0)
            .y(20.0)
            .z(30.0)
            .build()

        val serverLocation2 = ServerLocation.builder()
            .server("test-server")
            .world("test-world-2")
            .x(40.0)
            .y(50.0)
            .z(60.0)
            .build()

        val location1 = serverLocation1.toLocation()
        val location2 = serverLocation2.toLocation()

        assertNotNull(location1, "Location1 should not be null")
        assertNotNull(location2, "Location2 should not be null")
        assertEquals(world, location1!!.world, "Location1 should be in test-world")
        assertEquals(world2, location2!!.world, "Location2 should be in test-world-2")
    }

    // ========== Distance Method Tests ==========

    @Test
    fun testDistanceSameServerSameWorld() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = Location(world, 3.0, 4.0, 0.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        assertEquals(5.0, distance.get(), 0.001, "Distance should be 5.0 (3-4-5 triangle)")
    }

    @Test
    fun testDistanceZeroDistance() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(10.0)
            .y(20.0)
            .z(30.0)
            .build()

        val location = Location(world, 10.0, 20.0, 30.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        assertEquals(0.0, distance.get(), 0.001, "Distance should be 0 for same location")
    }

    @Test
    fun testDistance3D() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = Location(world, 3.0, 4.0, 5.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        // sqrt(3^2 + 4^2 + 5^2) = sqrt(9 + 16 + 25) = sqrt(50) ≈ 7.071
        assertEquals(7.071, distance.get(), 0.01, "Distance should be sqrt(50)")
    }

    @Test
    fun testDistanceNegativeCoordinates() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(-10.0)
            .y(-20.0)
            .z(-30.0)
            .build()

        val location = Location(world, -13.0, -24.0, -35.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        // sqrt(3^2 + 4^2 + 5^2) = sqrt(50) ≈ 7.071
        assertEquals(7.071, distance.get(), 0.01, "Distance should work with negative coordinates")
    }

    @Test
    fun testDistanceLargeValues() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = Location(world, 1000.0, 2000.0, 3000.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        // sqrt(1000^2 + 2000^2 + 3000^2) = sqrt(14000000) ≈ 3741.66
        assertEquals(3741.66, distance.get(), 1.0, "Distance should handle large values")
    }

    @Test
    fun testDistancePreciseValues() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.234567)
            .y(2.345678)
            .z(3.456789)
            .build()

        val location = Location(world, 4.567890, 5.678901, 6.789012)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isPresent, "Distance should be present")
        assertTrue(distance.get() > 0, "Distance should be positive")
        // Verify it's calculated correctly
        val expected = Math.sqrt(
            Math.pow(4.567890 - 1.234567, 2.0) +
                Math.pow(5.678901 - 2.345678, 2.0) +
                Math.pow(6.789012 - 3.456789, 2.0)
        )
        assertEquals(expected, distance.get(), 0.000001, "Distance should be precise")
    }

    @Test
    fun testDistanceDifferentServer() {
        val serverLocation = ServerLocation.builder()
            .server("different-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = Location(world, 3.0, 4.0, 0.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isEmpty, "Distance should be empty for different server")
    }

    @Test
    fun testDistanceDifferentWorld() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("different-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val location = Location(world, 3.0, 4.0, 0.0)

        val distance = serverLocation.distance(location)

        assertTrue(distance.isEmpty, "Distance should be empty for different world")
    }

    @Test
    fun testDistanceWithNullLocationWorld() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        // Create a location with null world (edge case)
        val location = Location(null, 3.0, 4.0, 0.0)

        // This should throw NullPointerException or return empty
        try {
            val distance = serverLocation.distance(location)
            assertTrue(distance.isEmpty, "Distance should be empty for null world")
        } catch (e: NullPointerException) {
            // This is acceptable behavior
            assertTrue(true, "Correctly handles null world")
        }
    }

    // ========== IsSameServer Method Tests ==========

    @Test
    fun testIsSameServer() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .build()

        assertTrue(serverLocation.isSameServer(), "Should be same server")

        val differentServer = ServerLocation.builder()
            .server("different-server")
            .world("test-world")
            .build()

        assertFalse(differentServer.isSameServer(), "Should not be same server")
    }

    @Test
    fun testIsSameServerWithNull() {
        val serverLocation = ServerLocation.builder()
            .server(null)
            .world("test-world")
            .build()

        // This should handle null gracefully
        try {
            val result = serverLocation.isSameServer()
            // If it doesn't throw, it should return false
            assertFalse(result, "Should return false for null server")
        } catch (e: NullPointerException) {
            // This is acceptable behavior
            assertTrue(true, "Correctly handles null server")
        }
    }

    @Test
    fun testIsSameServerWithEmptyString() {
        val serverLocation = ServerLocation.builder()
            .server("")
            .world("test-world")
            .build()

        assertFalse(serverLocation.isSameServer(), "Should return false for empty server name")
    }

    @Test
    fun testIsSameServerCaseSensitive() {
        val serverLocation = ServerLocation.builder()
            .server("TEST-SERVER")
            .world("test-world")
            .build()

        // Should be case-sensitive
        assertFalse(serverLocation.isSameServer(), "Should be case-sensitive")
    }

    // ========== Builder Tests ==========

    @Test
    fun testBuilder() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .yaw(45.0f)
            .pitch(90.0f)
            .build()

        assertEquals("test-server", serverLocation.server)
        assertEquals("test-world", serverLocation.world)
        assertEquals(1.0, serverLocation.x)
        assertEquals(2.0, serverLocation.y)
        assertEquals(3.0, serverLocation.z)
        assertEquals(45.0f, serverLocation.yaw)
        assertEquals(90.0f, serverLocation.pitch)
    }

    @Test
    fun testBuilderWithDefaults() {
        val serverLocation = ServerLocation.builder()
            .build()

        assertNull(serverLocation.server, "Server should be null by default")
        assertNull(serverLocation.world, "World should be null by default")
        assertEquals(0.0, serverLocation.x, "X should be 0 by default")
        assertEquals(0.0, serverLocation.y, "Y should be 0 by default")
        assertEquals(0.0, serverLocation.z, "Z should be 0 by default")
        assertEquals(0.0f, serverLocation.yaw, "Yaw should be 0 by default")
        assertEquals(0.0f, serverLocation.pitch, "Pitch should be 0 by default")
    }

    @Test
    fun testBuilderPartial() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .x(10.0)
            .build()

        assertEquals("test-server", serverLocation.server)
        assertNull(serverLocation.world, "World should be null if not set")
        assertEquals(10.0, serverLocation.x)
        assertEquals(0.0, serverLocation.y, "Y should be 0 if not set")
    }

    @Test
    fun testBuilderChaining() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .yaw(4.0f)
            .pitch(5.0f)
            .build()

        assertNotNull(serverLocation, "Should build successfully")
        assertEquals("test-server", serverLocation.server)
    }

    // ========== Data Class Tests ==========

    @Test
    fun testEquals() {
        val loc1 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .yaw(45.0f)
            .pitch(90.0f)
            .build()

        val loc2 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .yaw(45.0f)
            .pitch(90.0f)
            .build()

        assertEquals(loc1, loc2, "Equal locations should be equal")
    }

    @Test
    fun testNotEquals() {
        val loc1 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .build()

        val loc2 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(2.0)
            .y(2.0)
            .z(3.0)
            .build()

        assertNotEquals(loc1, loc2, "Different locations should not be equal")
    }

    @Test
    fun testHashCode() {
        val loc1 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .build()

        val loc2 = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .build()

        assertEquals(loc1.hashCode(), loc2.hashCode(), "Equal locations should have same hashCode")
    }

    @Test
    fun testToString() {
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(1.0)
            .y(2.0)
            .z(3.0)
            .build()

        val str = serverLocation.toString()
        assertNotNull(str, "toString should not be null")
        assertTrue(str.contains("test-server"), "toString should contain server")
        assertTrue(str.contains("test-world"), "toString should contain world")
    }

    // ========== Edge Cases and Integration Tests ==========

    @Test
    fun testRoundTripConversion() {
        val originalLocation = Location(world, 10.5, 20.0, 30.5, 45.0f, 90.0f)
        val serverLocation = ServerLocation.of(originalLocation)
        val convertedLocation = serverLocation.toLocation()

        assertNotNull(convertedLocation, "Converted location should not be null")
        assertEquals(originalLocation.x, convertedLocation!!.x, 0.001, "X should match after round trip")
        assertEquals(originalLocation.y, convertedLocation.y, 0.001, "Y should match after round trip")
        assertEquals(originalLocation.z, convertedLocation.z, 0.001, "Z should match after round trip")
        assertEquals(originalLocation.yaw, convertedLocation.yaw, 0.001f, "Yaw should match after round trip")
        assertEquals(originalLocation.pitch, convertedLocation.pitch, 0.001f, "Pitch should match after round trip")
    }

    @Test
    fun testMultipleConversions() {
        val locations = listOf(
            Location(world, 1.0, 2.0, 3.0, 0.0f, 0.0f),
            Location(world, 10.0, 20.0, 30.0, 45.0f, 90.0f),
            Location(world, -5.0, -10.0, -15.0, -45.0f, -90.0f)
        )

        locations.forEach { location ->
            val serverLocation = ServerLocation.of(location)
            assertNotNull(serverLocation, "Should convert location")
            assertEquals(location.x, serverLocation.x, 0.001, "X should match")
            assertEquals(location.y, serverLocation.y, 0.001, "Y should match")
            assertEquals(location.z, serverLocation.z, 0.001, "Z should match")
        }
    }

    @Test
    fun testDistanceCalculationAccuracy() {
        // Test Pythagorean theorem: 3-4-5 triangle
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        val testCases = listOf(
            Triple(3.0, 4.0, 0.0) to 5.0,
            Triple(5.0, 12.0, 0.0) to 13.0,
            Triple(8.0, 15.0, 0.0) to 17.0
        )

        testCases.forEach { (coords, expectedDistance) ->
            val location = Location(world, coords.first, coords.second, coords.third)
            val distance = serverLocation.distance(location)
            assertTrue(distance.isPresent, "Distance should be present")
            assertEquals(expectedDistance, distance.get(), 0.001, "Distance should match expected")
        }
    }

    @Test
    fun testDistanceWithYawPitch() {
        // Yaw and pitch should not affect distance calculation
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .yaw(45.0f)
            .pitch(90.0f)
            .build()

        val location1 = Location(world, 3.0, 4.0, 0.0, 0.0f, 0.0f)
        val location2 = Location(world, 3.0, 4.0, 0.0, 180.0f, -90.0f)

        val distance1 = serverLocation.distance(location1)
        val distance2 = serverLocation.distance(location2)

        assertTrue(distance1.isPresent, "Distance1 should be present")
        assertTrue(distance2.isPresent, "Distance2 should be present")
        assertEquals(distance1.get(), distance2.get(), 0.001, "Distance should not depend on yaw/pitch")
    }

    @Test
    fun testNullSafety() {
        // Test that methods handle null gracefully where appropriate
        val serverLocation = ServerLocation.builder()
            .server("test-server")
            .world("test-world")
            .x(0.0)
            .y(0.0)
            .z(0.0)
            .build()

        // toLocation with non-existent world returns null (tested above)
        // distance with null world location should handle gracefully
        val nullWorldLocation = Location(null, 0.0, 0.0, 0.0)
        try {
            val distance = serverLocation.distance(nullWorldLocation)
            // If it doesn't throw, it should return empty
            assertTrue(distance.isEmpty, "Should return empty for null world")
        } catch (e: NullPointerException) {
            // Acceptable behavior
            assertTrue(true, "Correctly handles null world")
        }
    }
}
