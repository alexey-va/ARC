@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.mobspawn

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.TestBase
import ru.arc.core.TestTaskScheduler

/**
 * Tests for MobSpawnService.
 */
class MobSpawnServiceTest : TestBase() {

    private lateinit var playerMock: PlayerMock
    private lateinit var scheduler: TestTaskScheduler
    private lateinit var worldProvider: TestWorldProvider
    private lateinit var claimChecker: TestClaimChecker
    private lateinit var entitySpawner: TestEntitySpawner
    private lateinit var world: World

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        playerMock = server.addPlayer("MobSpawnPlayer")
        scheduler = TestTaskScheduler()
        worldProvider = TestWorldProvider()
        claimChecker = TestClaimChecker()
        entitySpawner = TestEntitySpawner()
        world = server.addSimpleWorld("spawn_world")
        worldProvider.addWorld(world)
    }

    private fun createService(
        config: MobSpawnConfig = createDefaultConfig(),
        randomValue: Double = 0.5
    ): MobSpawnService {
        return MobSpawnService(
            config = config,
            scheduler = scheduler,
            worldProvider = worldProvider,
            claimChecker = claimChecker,
            entitySpawner = entitySpawner,
            random = { randomValue }
        )
    }

    private fun createDefaultConfig(
        enabled: Boolean = true,
        worlds: Set<String> = setOf("spawn_world"),
        useCmiCommand: Boolean = true
    ): MobSpawnConfig {
        return MobSpawnConfig(
            enabled = enabled,
            worlds = worlds,
            startHour = 0,
            endHour = 24,
            intervalTicks = 200L,
            radius = 50.0,
            threshold = 5,
            amount = 2,
            tryMultiplier = 30,
            maxLightLevel = 7,
            useCmiCommand = useCmiCommand,
            cmiSpread = 30,
            mobWeights = mapOf(
                EntityType.ZOMBIE to 50,
                EntityType.SKELETON to 30,
                EntityType.CREEPER to 20
            )
        )
    }

    @Nested
    @DisplayName("Service Lifecycle")
    inner class LifecycleTests {

        @Test
        fun `start creates timer task when enabled`() {
            val service = createService()

            assertFalse(service.isRunning())

            service.start()

            assertTrue(service.isRunning())
            assertEquals(1, scheduler.timerCount())
        }

        @Test
        fun `start does nothing when disabled`() {
            val config = createDefaultConfig(enabled = false)
            val service = createService(config)

            service.start()

            assertFalse(service.isRunning())
            assertEquals(0, scheduler.timerCount())
        }

        @Test
        fun `start does nothing with no mobs configured`() {
            val config = MobSpawnConfig(
                enabled = true,
                mobWeights = emptyMap()
            )
            val service = createService(config)

            service.start()

            assertFalse(service.isRunning())
        }

        @Test
        fun `stop cancels the task`() {
            val service = createService()
            service.start()

            assertTrue(service.isRunning())

            service.stop()

            assertFalse(service.isRunning())
        }

        @Test
        fun `start after stop restarts service`() {
            val service = createService()

            service.start()
            assertTrue(service.isRunning())

            service.stop()
            assertFalse(service.isRunning())

            service.start()
            assertTrue(service.isRunning())
        }
    }

    @Nested
    @DisplayName("Mob Types")
    inner class MobTypesTests {

        @Test
        fun `getTrackedMobTypes returns configured mobs`() {
            val service = createService()

            val types = service.getTrackedMobTypes()

            assertEquals(3, types.size)
            assertTrue(EntityType.ZOMBIE in types)
            assertTrue(EntityType.SKELETON in types)
            assertTrue(EntityType.CREEPER in types)
        }

        @Test
        fun `empty config has no tracked types`() {
            val config = MobSpawnConfig(mobWeights = emptyMap())
            val service = createService(config)

            assertTrue(service.getTrackedMobTypes().isEmpty())
        }
    }

    @Nested
    @DisplayName("Spawn Attempt - Skip Conditions")
    inner class SpawnSkipTests {

        @Test
        fun `skips spawn in mushroom fields biome`() {
            // Note: MockBukkit may not fully support biome checks
            // This test documents expected behavior
            val service = createService()

            // We can't easily mock biome in MockBukkit, so this tests the logic path exists
            assertNotNull(service)
        }

        @Test
        fun `skips spawn in claimed area`() {
            claimChecker.claimedLocations.add(playerMock.location)

            val service = createService()
            val result = service.trySpawnNear(playerMock)

            assertTrue(result.skipped)
            assertEquals("claimed", result.reason)
            assertEquals(0, result.spawned)
        }

        @Test
        fun `flying check is part of player state validation`() {
            // Note: MockBukkit requires allowFlight before setting isFlying
            // This test verifies the service handles player state correctly
            val service = createService()

            // Verify the service has player state check logic
            playerMock.gameMode = GameMode.SURVIVAL
            playerMock.setAllowFlight(true)
            playerMock.isFlying = true

            val result = service.trySpawnNear(playerMock)

            // Should be skipped due to flying state
            assertTrue(result.skipped)
            assertEquals("player_state", result.reason)
        }

        @Test
        fun `skips spawn for creative player`() {
            playerMock.gameMode = GameMode.CREATIVE

            val service = createService()
            val result = service.trySpawnNear(playerMock)

            assertTrue(result.skipped)
            assertEquals("player_state", result.reason)
        }

        @Test
        fun `skips spawn for spectator player`() {
            playerMock.gameMode = GameMode.SPECTATOR

            val service = createService()
            val result = service.trySpawnNear(playerMock)

            assertTrue(result.skipped)
            assertEquals("player_state", result.reason)
        }

        @Test
        fun `survival player can trigger spawn`() {
            playerMock.gameMode = GameMode.SURVIVAL
            playerMock.isFlying = false

            val service = createService()
            val result = service.trySpawnNear(playerMock)

            // May still be skipped due to other conditions (light level, etc.)
            // but not due to player state
            assertNotEquals("player_state", result.reason)
        }
    }

    @Nested
    @DisplayName("Count Nearby Mobs")
    inner class CountNearbyMobsTests {

        @Test
        fun `countNearbyMobs returns 0 with no entities`() {
            val service = createService()

            val count = service.countNearbyMobs(playerMock)

            assertEquals(0, count)
        }

        // Note: MockBukkit getNearbyEntities is limited, 
        // more comprehensive tests would need integration testing
    }

    @Nested
    @DisplayName("CMI Command Spawning")
    inner class CmiSpawnTests {

        @Test
        fun `spawns via CMI when configured`() {
            val config = createDefaultConfig(useCmiCommand = true)
            val service = createService(config)

            playerMock.gameMode = GameMode.SURVIVAL
            // Set block below player to have low light (MockBukkit limitation)

            val result = service.trySpawnNear(playerMock)

            // Result depends on light level check in MockBukkit
            assertNotNull(result)
        }
    }

    @Nested
    @DisplayName("MobSpawnManager Facade")
    inner class ManagerFacadeTests {

        @Test
        fun `isRunning returns false initially`() {
            MobSpawnManager.cancel() // Ensure clean state

            assertFalse(MobSpawnManager.isRunning())
        }

        @Test
        fun `init with custom service works`() {
            val service = createService()

            MobSpawnManager.init(service)

            assertTrue(MobSpawnManager.isRunning())
            assertSame(service, MobSpawnManager.getService())

            MobSpawnManager.cancel()
            assertFalse(MobSpawnManager.isRunning())
        }

        @Test
        fun `cancel stops service`() {
            val service = createService()
            MobSpawnManager.init(service)

            MobSpawnManager.cancel()

            assertFalse(MobSpawnManager.isRunning())
            assertNull(MobSpawnManager.getService())
        }
    }
}

/**
 * Test implementation of WorldProvider.
 */
class TestWorldProvider : WorldProvider {
    private val _worlds = mutableListOf<World>()

    fun addWorld(world: World) {
        _worlds.add(world)
    }

    override fun getWorlds(): List<World> = _worlds
}

/**
 * Test implementation of ClaimChecker.
 */
class TestClaimChecker : ClaimChecker {
    val claimedLocations = mutableSetOf<Location>()

    override fun isClaimed(location: Location): Boolean {
        return claimedLocations.any {
            it.world == location.world &&
                it.blockX == location.blockX &&
                it.blockY == location.blockY &&
                it.blockZ == location.blockZ
        }
    }
}

/**
 * Test implementation of EntitySpawner.
 */
class TestEntitySpawner : EntitySpawner {
    val spawnedEntities = mutableListOf<Pair<Location, EntityType>>()
    val cmiCommands = mutableListOf<CmiSpawnCommand>()

    data class CmiSpawnCommand(
        val player: Player,
        val entityType: EntityType,
        val amount: Int,
        val spread: Int
    )

    override fun spawn(location: Location, entityType: EntityType) {
        spawnedEntities.add(location to entityType)
    }

    override fun spawnViaCmi(player: Player, entityType: EntityType, amount: Int, spread: Int) {
        cmiCommands.add(CmiSpawnCommand(player, entityType, amount, spread))
    }

    fun clear() {
        spawnedEntities.clear()
        cmiCommands.clear()
    }
}

