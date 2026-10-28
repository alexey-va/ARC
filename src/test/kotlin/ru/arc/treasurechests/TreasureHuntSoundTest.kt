package ru.arc.treasurechests

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.block.Block
import ru.arc.ARC
import ru.arc.common.chests.CustomChest
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.TestConfig
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.util.ParticleManager
import ru.arc.util.SoundUtils

class TreasureHuntSoundTest : FreeSpec({
    "each unclaimed chest sounds at its centre beyond the particle radius and stops after claim or shutdown" {
        MockBukkitTestRuntime.open().use { paper ->
            val previousServer = ARC.serverName
            ARC.serverName = "hunt-sound-test"
            val world = paper.addSimpleWorld("hunt-sounds")
            val player = paper.addPlayer("listener")
            player.teleport(Location(world, 4.5, 70.5, 0.5))
            val first = world.getBlockAt(0, 70, 0)
            val second = world.getBlockAt(8, 70, 0)
            val distant = world.getBlockAt(40, 70, 0)
            world.loadChunk(0, 0)
            world.loadChunk(2, 0)
            val pool = LocationPoolManager.createEphemeralPool()
            listOf(first, second, distant).forEach { pool.addLocation(it.location) }
            val config = TreasureHuntModuleConfig(TestConfig(mapOf(
                "idle.ticks" to 5,
                "idle.player-sound-each" to 11,
                "idle.default.radius" to 1,
                "idle.default.sound-radius" to 12,
                "idle.default.sound" to "minecraft:block.amethyst_block.chime",
                "idle.default.sound-volume" to 1.2,
                "idle.default.sound-pitch" to 1.3,
            )))
            val spawner = mockk<ChestSpawner>()
            every { spawner.createChest(any(), ChestVariant.VANILLA, null) } answers {
                val block = firstArg<Block>()
                mockk<CustomChest>(relaxed = true) {
                    every { this@mockk.block } returns block
                    every { create() } returns true
                }
            }
            val scheduler = TestTaskScheduler()
            val states = mutableListOf<List<Int>>()
            val service = TreasureHuntService(config, scheduler, mockk(relaxed = true), spawner) { hunts ->
                states += hunts.map { it.remainingChests }
            }
            SoundUtils.clearCache()
            player.clearSounds()
            fun idleSounds() = player.heardSounds.filter { it.sound.substringAfter(':') == "block.amethyst_block.chime" }
            try {
                val hunt = service.startHunt(
                    TreasureHuntConfig.simple("sound", pool.id, ChestType.vanilla("missing-test-rewards"))
                        .copy(effects = EffectsConfig(launchFireworks = false)),
                    3, false,
                ).shouldNotBeNull()
                states shouldBe listOf(listOf(3))
                scheduler.tick(5)
                idleSounds().size shouldBe 2
                idleSounds().map { it.location }.toSet() shouldBe setOf(first.location.toCenterLocation(), second.location.toCenterLocation())
                idleSounds().forEach { sound ->
                    sound.volume shouldBe 1.2f
                    sound.pitch shouldBe 1.3f
                }

                service.claimChest(first, player) shouldBe true
                states.last() shouldBe listOf(2)
                hunt.bossBar.shouldNotBeNull().progress() shouldBe 2f / 3f
                scheduler.tick(59)
                idleSounds().size shouldBe 2
                scheduler.tick()
                idleSounds().count { it.location == first.location.toCenterLocation() } shouldBe 1
                idleSounds().count { it.location == second.location.toCenterLocation() } shouldBe 2

                service.shutdown()
                states.last() shouldBe emptyList()
                scheduler.timerCount() shouldBe 0
                scheduler.tick(120)
                idleSounds().size shouldBe 3
            } finally {
                service.shutdown()
                LocationPoolManager.removeEphemeralPool(pool.id)
                ParticleManager.stopTasks()
                SoundUtils.clearCache()
                ARC.serverName = previousServer
            }
        }
    }

    "an unloaded chest chunk stays silent and is never loaded by the display tick" {
        MockBukkitTestRuntime.open().use { paper ->
            val previousServer = ARC.serverName
            ARC.serverName = "hunt-sound-test"
            val world = paper.addSimpleWorld("hunt-unloaded-sounds")
            val player = paper.addPlayer("near-unloaded")
            player.teleport(Location(world, 15.5, 70.5, 0.5))
            val block = world.getBlockAt(16, 70, 0)
            world.loadChunk(0, 0)
            world.loadChunk(1, 0)
            val pool = LocationPoolManager.createEphemeralPool().also { it.addLocation(block.location) }
            val chest = mockk<CustomChest>(relaxed = true) {
                every { this@mockk.block } returns block
                every { create() } returns true
            }
            val spawner = mockk<ChestSpawner>()
            every { spawner.createChest(any(), any(), any()) } returns chest
            val scheduler = TestTaskScheduler()
            val service = TreasureHuntService(
                TreasureHuntModuleConfig(TestConfig(mapOf("idle.ticks" to 5))),
                scheduler, mockk(relaxed = true), spawner,
            )
            SoundUtils.clearCache()
            player.clearSounds()
            try {
                service.startHunt(pool, 1, ChestType.vanilla("missing-test-rewards"), false).shouldNotBeNull()
                world.unloadChunk(1, 0, false) shouldBe true
                scheduler.tick(60)
                world.isChunkLoaded(1, 0) shouldBe false
                player.heardSounds shouldBe emptyList()
            } finally {
                service.shutdown()
                LocationPoolManager.removeEphemeralPool(pool.id)
                ParticleManager.stopTasks()
                SoundUtils.clearCache()
                ARC.serverName = previousServer
            }
        }
    }
})
