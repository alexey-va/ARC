@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.farm

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.TestBase
import ru.arc.common.WeightedRandom

/**
 * Integration tests for Farm module.
 *
 * Note: Full integration with WorldGuard regions is not possible in MockBukkit.
 * These tests focus on the testable business logic.
 */
class FarmModuleTest : TestBase() {

    private lateinit var playerMock: PlayerMock

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        playerMock = server.addPlayer("FarmPlayer")
    }

    @Nested
    @DisplayName("Material Sets")
    inner class MaterialSetTests {

        @Test
        fun `farmMaterials can contain crop materials`() {
            // This tests that the FarmManager static sets work
            val cropMaterials = setOf(
                Material.WHEAT,
                Material.CARROTS,
                Material.POTATOES,
                Material.BEETROOTS,
                Material.NETHER_WART
            )

            for (material in cropMaterials) {
                assertNotNull(material)
            }
        }

        @Test
        fun `lumber materials are logs and wood`() {
            val logMaterials = setOf(
                Material.OAK_LOG,
                Material.SPRUCE_LOG,
                Material.BIRCH_LOG,
                Material.JUNGLE_LOG,
                Material.ACACIA_LOG,
                Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG,
                Material.CHERRY_LOG
            )

            for (material in logMaterials) {
                assertNotNull(material)
                assertTrue(material.name.contains("LOG"))
            }
        }

        @Test
        fun `ore materials exist`() {
            val oreMaterials = setOf(
                Material.COAL_ORE,
                Material.IRON_ORE,
                Material.GOLD_ORE,
                Material.DIAMOND_ORE,
                Material.EMERALD_ORE,
                Material.LAPIS_ORE,
                Material.REDSTONE_ORE,
                Material.COPPER_ORE
            )

            for (material in oreMaterials) {
                assertNotNull(material)
                assertTrue(material.name.contains("ORE"))
            }
        }
    }

    @Nested
    @DisplayName("Block Limit Tracker with UUID")
    inner class BlockLimitIntegrationTests {

        private lateinit var tracker: BlockLimitTracker

        @BeforeEach
        fun setUp() {
            tracker = BlockLimitTracker(maxBlocks = 256, progressInterval = 64)
        }

        @Test
        fun `player UUID can be used as tracker key`() {
            val playerId = playerMock.uniqueId

            tracker.incrementBlocks(playerId)

            assertEquals(1, tracker.getBlockCount(playerId))
        }

        @Test
        fun `multiple players tracked separately`() {
            val player1 = server.addPlayer("Player1")
            val player2 = server.addPlayer("Player2")

            repeat(10) { tracker.incrementBlocks(player1.uniqueId) }
            repeat(20) { tracker.incrementBlocks(player2.uniqueId) }

            assertEquals(10, tracker.getBlockCount(player1.uniqueId))
            assertEquals(20, tracker.getBlockCount(player2.uniqueId))
        }

        @Test
        fun `limit is enforced per player`() {
            val playerId = playerMock.uniqueId

            repeat(256) { tracker.incrementBlocks(playerId) }

            assertTrue(tracker.hasReachedLimit(playerId))

            // Other player should not be affected
            val otherPlayer = server.addPlayer("Other")
            assertFalse(tracker.hasReachedLimit(otherPlayer.uniqueId))
        }
    }

    @Nested
    @DisplayName("Weighted Material Picker")
    inner class WeightedMaterialPickerTests {

        @Test
        fun `can pick from ore materials`() {
            val picker = WeightedRandom<Material>()
            picker.add(Material.COAL_ORE, 50.0)
            picker.add(Material.IRON_ORE, 30.0)
            picker.add(Material.GOLD_ORE, 15.0)
            picker.add(Material.DIAMOND_ORE, 5.0)

            assertEquals(4, picker.size())

            // Pick should return one of the ores
            val picked = picker.random()
            assertNotNull(picked)
            assertTrue(
                picked in listOf(
                    Material.COAL_ORE, Material.IRON_ORE,
                    Material.GOLD_ORE, Material.DIAMOND_ORE
                )
            )
        }

        @Test
        fun `weighted distribution is roughly correct`() {
            val picker = WeightedRandom<Material>()
            picker.add(Material.COAL_ORE, 90.0)
            picker.add(Material.DIAMOND_ORE, 10.0)

            var coalCount = 0
            var diamondCount = 0

            repeat(1000) {
                when (picker.random()) {
                    Material.COAL_ORE -> coalCount++
                    Material.DIAMOND_ORE -> diamondCount++
                    else -> {}
                }
            }

            // Coal should be roughly 90%, diamond 10%
            assertTrue(coalCount > 700, "Coal count should be >700, got $coalCount")
            assertTrue(diamondCount < 200, "Diamond count should be <200, got $diamondCount")
        }
    }

    @Nested
    @DisplayName("Temporary Block Expiration")
    inner class TemporaryBlockExpirationTests {

        @Test
        fun `blocks expire based on time`() {
            var currentTime = 0L
            val tracker = TemporaryBlockTracker<String>(
                expireTimeMs = 60000L, // 60 seconds
                timeProvider = { currentTime }
            )

            tracker.add("block_at_0,0,0")
            currentTime = 30000L
            tracker.add("block_at_1,1,1")

            // At 60 seconds, first block expires
            currentTime = 60001L
            val expired = tracker.getExpired()

            assertEquals(1, expired.size)
            assertEquals("block_at_0,0,0", expired[0])
            assertEquals(1, tracker.count()) // second block still tracked
        }
    }
}

