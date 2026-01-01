@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.autobuild

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for BuildingManager utility methods that don't require MockBukkit.
 */
class BuildingManagerUtilTest {

    // ========== rotationFromYaw Tests ==========

    @Test
    fun testRotationFromYawNorth() {
        // Yaw 180 (facing north) + 180 = 360, which is > 315 -> rotation 0
        assertEquals(0, BuildingManager.rotationFromYaw(180f), "Facing north should be rotation 0")
    }

    @Test
    fun testRotationFromYawEast() {
        // Yaw -90 (facing east) + 180 = 90, which is <= 135 -> rotation 90
        assertEquals(90, BuildingManager.rotationFromYaw(-90f), "Facing east should be rotation 90")
    }

    @Test
    fun testRotationFromYawSouth() {
        // Yaw 0 (facing south) + 180 = 180, which is <= 225 -> rotation 180
        assertEquals(180, BuildingManager.rotationFromYaw(0f), "Facing south should be rotation 180")
    }

    @Test
    fun testRotationFromYawWest() {
        // Yaw 90 (facing west) + 180 = 270, which is > 225 -> rotation 270
        assertEquals(270, BuildingManager.rotationFromYaw(90f), "Facing west should be rotation 270")
    }

    @Test
    fun testRotationFromYawBoundary45() {
        // Yaw -135 + 180 = 45, which is <= 45 -> rotation 0
        assertEquals(0, BuildingManager.rotationFromYaw(-135f), "Yaw -135 should be rotation 0")
    }

    @Test
    fun testRotationFromYawBoundary135() {
        // Yaw -45 + 180 = 135, which is <= 135 -> rotation 90
        assertEquals(90, BuildingManager.rotationFromYaw(-45f), "Yaw -45 should be rotation 90")
    }

    @Test
    fun testRotationFromYawBoundary225() {
        // Yaw 45 + 180 = 225, which is <= 225 -> rotation 180
        assertEquals(180, BuildingManager.rotationFromYaw(45f), "Yaw 45 should be rotation 180")
    }

    @Test
    fun testRotationFromYawBoundary315() {
        // Yaw 135 + 180 = 315, which is > 225 but <= 315 -> rotation 270
        assertEquals(270, BuildingManager.rotationFromYaw(135f), "Yaw 135 should be rotation 270")
    }

    @Test
    fun testRotationFromYawNegative() {
        // Yaw -180 + 180 = 0, which is <= 45 -> rotation 0
        assertEquals(0, BuildingManager.rotationFromYaw(-180f), "Yaw -180 should be rotation 0")
    }

    @Test
    fun testRotationFromYawLarge() {
        // Yaw 360 + 180 = 540, which is > 315 -> rotation 0
        assertEquals(0, BuildingManager.rotationFromYaw(360f), "Yaw 360 should be rotation 0")
    }

    // ========== Default Skins Map Tests ==========

    @Test
    fun testDefaultSkinsNotEmpty() {
        assertFalse(BuildConfig.defaultNpcSkins.isEmpty(), "Default skins map should not be empty")
    }

    @Test
    fun testDefaultSkinsHasValidUrls() {
        for ((name, url) in BuildConfig.defaultNpcSkins) {
            assertTrue(name.isNotEmpty(), "Skin name should not be empty")
            assertTrue(url.startsWith("https://"), "Skin URL should be HTTPS")
        }
    }
}

