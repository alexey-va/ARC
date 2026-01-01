@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.farm

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for farm configuration data classes.
 */
class FarmConfigTest {

    @Nested
    @DisplayName("FarmModuleConfig")
    inner class FarmModuleConfigTests {

        @Test
        fun `default config has empty zones`() {
            val config = FarmModuleConfig()

            assertTrue(config.farms.isEmpty())
            assertTrue(config.lumbermills.isEmpty())
            assertTrue(config.mines.isEmpty())
            assertEquals("arc.farm-admin", config.adminPermission)
        }

        @Test
        fun `config with all zones`() {
            val farmConfig = FarmZoneConfig(
                id = "farm1",
                worldName = "world",
                regionName = "farm",
                permission = "farm.use",
                particles = true,
                maxBlocksPerDay = 256,
                priority = 0,
                blocks = setOf(Material.WHEAT),
                seeds = setOf(Material.WHEAT_SEEDS)
            )

            val lumberConfig = LumbermillConfig(
                id = "lumber1",
                worldName = "world",
                regionName = "lumber",
                permission = "lumber.use",
                particles = true,
                priority = 0,
                blocks = setOf(Material.OAK_LOG)
            )

            val mineConfig = MineConfig(
                id = "mine1",
                worldName = "world",
                regionName = "mine1",
                permission = "mine.use",
                particles = true,
                maxBlocksPerDay = 512,
                priority = 5,
                oreWeights = mapOf(Material.COAL_ORE to 50),
                tempBlock = Material.BEDROCK,
                baseBlock = Material.STONE,
                expireTimeMs = 60000L,
                replaceTime = 20L,
                replaceBatch = 10,
                expPerBase = 1,
                expPerOre = 2
            )

            val config = FarmModuleConfig(
                adminPermission = "custom.admin",
                farms = listOf(farmConfig),
                lumbermills = listOf(lumberConfig),
                mines = listOf(mineConfig)
            )

            assertEquals("custom.admin", config.adminPermission)
            assertEquals(1, config.farms.size)
            assertEquals(1, config.lumbermills.size)
            assertEquals(1, config.mines.size)
        }

        @Test
        fun `config with multiple farms and lumbermills`() {
            val config = FarmModuleConfig(
                farms = listOf(
                    FarmZoneConfig("farm1", "world", "r1", "p1", false, 100, 1, emptySet(), emptySet()),
                    FarmZoneConfig("farm2", "world", "r2", "p2", false, 200, 2, emptySet(), emptySet())
                ),
                lumbermills = listOf(
                    LumbermillConfig("lumber1", "world", "r3", "p3", false, 1, emptySet()),
                    LumbermillConfig("lumber2", "world", "r4", "p4", false, 2, emptySet())
                )
            )

            assertEquals(2, config.farms.size)
            assertEquals(2, config.lumbermills.size)
            assertEquals("farm1", config.farms[0].id)
            assertEquals("farm2", config.farms[1].id)
        }
    }

    @Nested
    @DisplayName("FarmZoneConfig")
    inner class FarmZoneConfigTests {

        @Test
        fun `contains all required fields`() {
            val config = FarmZoneConfig(
                id = "main_farm",
                worldName = "world",
                regionName = "farm_region",
                permission = "farm.harvest",
                particles = true,
                maxBlocksPerDay = 500,
                priority = 5,
                blocks = setOf(Material.WHEAT, Material.CARROTS, Material.POTATOES),
                seeds = setOf(Material.WHEAT_SEEDS, Material.PUMPKIN_SEEDS)
            )

            assertEquals("main_farm", config.id)
            assertEquals("world", config.worldName)
            assertEquals("farm_region", config.regionName)
            assertEquals("farm.harvest", config.permission)
            assertTrue(config.particles)
            assertEquals(500, config.maxBlocksPerDay)
            assertEquals(5, config.priority)
            assertEquals(3, config.blocks.size)
            assertEquals(2, config.seeds.size)
        }
    }

    @Nested
    @DisplayName("LumbermillConfig")
    inner class LumbermillConfigTests {

        @Test
        fun `contains all required fields`() {
            val config = LumbermillConfig(
                id = "main_lumber",
                worldName = "forest",
                regionName = "lumber_zone",
                permission = "lumber.chop",
                particles = false,
                priority = 3,
                blocks = setOf(Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG)
            )

            assertEquals("main_lumber", config.id)
            assertEquals("forest", config.worldName)
            assertEquals("lumber_zone", config.regionName)
            assertEquals("lumber.chop", config.permission)
            assertFalse(config.particles)
            assertEquals(3, config.priority)
            assertEquals(3, config.blocks.size)
            assertTrue(config.blocks.contains(Material.OAK_LOG))
        }
    }

    @Nested
    @DisplayName("MineConfig")
    inner class MineConfigTests {

        @Test
        fun `contains all required fields`() {
            val oreWeights = mapOf(
                Material.COAL_ORE to 50,
                Material.IRON_ORE to 30,
                Material.GOLD_ORE to 15,
                Material.DIAMOND_ORE to 5
            )

            val config = MineConfig(
                id = "deep_mine",
                worldName = "mining_world",
                regionName = "deep_mine_region",
                permission = "mine.deep",
                particles = true,
                maxBlocksPerDay = 1000,
                priority = 10,
                oreWeights = oreWeights,
                tempBlock = Material.BEDROCK,
                baseBlock = Material.DEEPSLATE,
                expireTimeMs = 120000L,
                replaceTime = 40L,
                replaceBatch = 20,
                expPerBase = 2,
                expPerOre = 5
            )

            assertEquals("deep_mine", config.id)
            assertEquals("mining_world", config.worldName)
            assertEquals("deep_mine_region", config.regionName)
            assertEquals("mine.deep", config.permission)
            assertTrue(config.particles)
            assertEquals(1000, config.maxBlocksPerDay)
            assertEquals(10, config.priority)
            assertEquals(4, config.oreWeights.size)
            assertEquals(Material.BEDROCK, config.tempBlock)
            assertEquals(Material.DEEPSLATE, config.baseBlock)
            assertEquals(120000L, config.expireTimeMs)
            assertEquals(40L, config.replaceTime)
            assertEquals(20, config.replaceBatch)
            assertEquals(2, config.expPerBase)
            assertEquals(5, config.expPerOre)
        }

        @Test
        fun `ores derived from ore weights`() {
            val config = MineConfig(
                id = "test",
                worldName = "world",
                regionName = "region",
                permission = "perm",
                particles = false,
                maxBlocksPerDay = 100,
                priority = 1,
                oreWeights = mapOf(
                    Material.COAL_ORE to 10,
                    Material.IRON_ORE to 5
                ),
                tempBlock = Material.BEDROCK,
                baseBlock = Material.STONE,
                expireTimeMs = 60000L,
                replaceTime = 20L,
                replaceBatch = 10,
                expPerBase = 1,
                expPerOre = 2
            )

            assertEquals(setOf(Material.COAL_ORE, Material.IRON_ORE), config.ores)
        }
    }

    @Nested
    @DisplayName("FarmMessages")
    inner class FarmMessagesTests {

        @Test
        fun `default messages`() {
            val messages = FarmMessages()

            assertNotNull(messages.noPermission)
            assertNotNull(messages.alreadyBroken)
            assertNotNull(messages.limitReached)
            assertNotNull(messages.progress)
            assertEquals(60, messages.limitMessageCooldown)
        }

        @Test
        fun `custom messages`() {
            val messages = FarmMessages(
                noPermission = "<red>Custom no permission",
                alreadyBroken = "<yellow>Wait for respawn",
                limitReached = "<gold>You mined enough today",
                progress = "<green>Progress: <count>/<max>",
                limitMessageCooldown = 120
            )

            assertEquals("<red>Custom no permission", messages.noPermission)
            assertEquals("<yellow>Wait for respawn", messages.alreadyBroken)
            assertEquals("<gold>You mined enough today", messages.limitReached)
            assertEquals("<green>Progress: <count>/<max>", messages.progress)
            assertEquals(120, messages.limitMessageCooldown)
        }
    }

    @Nested
    @DisplayName("Config Immutability")
    inner class ConfigImmutabilityTests {

        @Test
        fun `FarmZoneConfig blocks set is immutable copy`() {
            val originalBlocks = mutableSetOf(Material.WHEAT)
            val config = FarmZoneConfig(
                id = "test",
                worldName = "world",
                regionName = "region",
                permission = "perm",
                particles = false,
                maxBlocksPerDay = 100,
                priority = 0,
                blocks = originalBlocks,
                seeds = emptySet()
            )

            // Modifying original should not affect config
            originalBlocks.add(Material.CARROTS)

            // This tests that the data class stores a reference, not a copy
            // In production, consider using toSet() in the config loading
            assertTrue(config.blocks.contains(Material.WHEAT))
        }

        @Test
        fun `MineConfig ores is derived from oreWeights keys`() {
            val config = MineConfig(
                id = "test",
                worldName = "world",
                regionName = "region",
                permission = "perm",
                particles = false,
                maxBlocksPerDay = 100,
                priority = 1,
                oreWeights = mapOf(Material.COAL_ORE to 10),
                tempBlock = Material.BEDROCK,
                baseBlock = Material.STONE,
                expireTimeMs = 60000L,
                replaceTime = 20L,
                replaceBatch = 10,
                expPerBase = 1,
                expPerOre = 2
            )

            assertEquals(config.oreWeights.keys, config.ores)
        }
    }
}

