@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.mobspawn

import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for MobSpawnConfig.
 */
class MobSpawnConfigTest {

    @Nested
    @DisplayName("Default Values")
    inner class DefaultValuesTests {

        @Test
        fun `default config has sensible defaults`() {
            val config = MobSpawnConfig()

            assertTrue(config.enabled)
            assertTrue(config.worlds.isEmpty())
            assertEquals(13, config.startHour)
            assertEquals(0, config.endHour)
            assertEquals(200L, config.intervalTicks)
            assertEquals(50.0, config.radius)
            assertEquals(5, config.threshold)
            assertEquals(2, config.amount)
            assertEquals(30, config.tryMultiplier)
            assertEquals(7, config.maxLightLevel)
            assertTrue(config.useCmiCommand)
            assertEquals(30, config.cmiSpread)
            assertTrue(config.mobWeights.isEmpty())
        }
    }

    @Nested
    @DisplayName("Spawn Time Check")
    inner class SpawnTimeTests {

        @Test
        fun `isSpawnTime returns true during night`() {
            val config = MobSpawnConfig(startHour = 13, endHour = 0)

            // Night time: 13000-24000 ticks (13:00 - 0:00 in Minecraft time)
            assertTrue(config.isSpawnTime(13000L))
            assertTrue(config.isSpawnTime(18000L))
            assertTrue(config.isSpawnTime(23000L))
            assertTrue(config.isSpawnTime(0L))
        }

        @Test
        fun `isSpawnTime returns false during day`() {
            val config = MobSpawnConfig(startHour = 13, endHour = 0)

            // Day time: 1000-12999 ticks
            assertFalse(config.isSpawnTime(1000L))
            assertFalse(config.isSpawnTime(6000L))
            assertFalse(config.isSpawnTime(12000L))
        }

        @Test
        fun `isSpawnTime handles midnight correctly`() {
            val config = MobSpawnConfig(startHour = 18, endHour = 6)

            // Should spawn from 18:00 to 06:00
            assertTrue(config.isSpawnTime(18000L))
            assertTrue(config.isSpawnTime(0L))
            assertTrue(config.isSpawnTime(5000L))
            assertFalse(config.isSpawnTime(12000L))
        }

        @Test
        fun `isSpawnTime works for day range`() {
            val config = MobSpawnConfig(startHour = 6, endHour = 18)

            // Day range: 6000-18000 ticks
            assertTrue(config.isSpawnTime(6000L))
            assertTrue(config.isSpawnTime(12000L))
            assertTrue(config.isSpawnTime(18000L))
            assertFalse(config.isSpawnTime(20000L))
            assertFalse(config.isSpawnTime(0L))
        }
    }

    @Nested
    @DisplayName("Mob Picker Creation")
    inner class MobPickerTests {

        @Test
        fun `createMobPicker returns empty picker for no mobs`() {
            val config = MobSpawnConfig()
            val picker = config.createMobPicker()

            assertEquals(0, picker.size())
            assertNull(picker.random())
        }

        @Test
        fun `createMobPicker contains all configured mobs`() {
            val config = MobSpawnConfig(
                mobWeights = mapOf(
                    EntityType.ZOMBIE to 50,
                    EntityType.SKELETON to 30,
                    EntityType.CREEPER to 20
                )
            )

            val picker = config.createMobPicker()

            assertEquals(3, picker.size())

            // Should return one of the configured mobs
            val picked = picker.random()
            assertNotNull(picked)
            assertTrue(picked in listOf(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER))
        }

        @Test
        fun `createMobPicker respects weights roughly`() {
            val config = MobSpawnConfig(
                mobWeights = mapOf(
                    EntityType.ZOMBIE to 90,
                    EntityType.WITHER to 10
                )
            )

            val picker = config.createMobPicker()

            var zombieCount = 0
            var witherCount = 0

            repeat(1000) {
                when (picker.random()) {
                    EntityType.ZOMBIE -> zombieCount++
                    EntityType.WITHER -> witherCount++
                    else -> {}
                }
            }

            // Zombie should be ~90%, wither ~10%
            assertTrue(zombieCount > 700, "Zombie count should be >700, got $zombieCount")
            assertTrue(witherCount < 200, "Wither count should be <200, got $witherCount")
        }
    }

    @Nested
    @DisplayName("Configuration with Custom Values")
    inner class CustomConfigTests {

        @Test
        fun `config with custom values`() {
            val config = MobSpawnConfig(
                enabled = false,
                worlds = setOf("world", "world_nether"),
                startHour = 18,
                endHour = 6,
                intervalTicks = 400L,
                radius = 100.0,
                threshold = 10,
                amount = 5,
                tryMultiplier = 50,
                maxLightLevel = 5,
                useCmiCommand = false,
                cmiSpread = 50,
                mobWeights = mapOf(EntityType.ZOMBIE to 100)
            )

            assertFalse(config.enabled)
            assertEquals(setOf("world", "world_nether"), config.worlds)
            assertEquals(18, config.startHour)
            assertEquals(6, config.endHour)
            assertEquals(400L, config.intervalTicks)
            assertEquals(100.0, config.radius)
            assertEquals(10, config.threshold)
            assertEquals(5, config.amount)
            assertEquals(50, config.tryMultiplier)
            assertEquals(5, config.maxLightLevel)
            assertFalse(config.useCmiCommand)
            assertEquals(50, config.cmiSpread)
            assertEquals(1, config.mobWeights.size)
        }
    }
}


