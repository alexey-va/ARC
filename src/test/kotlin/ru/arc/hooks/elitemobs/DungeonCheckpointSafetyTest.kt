package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime

class DungeonCheckpointSafetyTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "safe floor accepts a standing player but a wall overlapping the body is rejected" {
        val world = paper.addSimpleWorld("body")
        world.getBlockAt(0, 69, 0).type = Material.STONE
        world.loadChunk(0, 0)
        val location = Location(world, 0.5, 70.0, 0.5)
        safeDungeonCheckpoint(location) shouldBe true
        world.getBlockAt(1, 70, 0).type = Material.STONE
        safeDungeonCheckpoint(location) shouldBe true
        safeDungeonCheckpoint(location.clone().apply { x = 0.8 }) shouldBe false
    }

    "hazardous floor, head water, invalid angles and void are rejected" {
        val world = paper.addSimpleWorld("hazards")
        world.loadChunk(0, 0)
        val location = Location(world, 0.5, 70.0, 0.5)
        safeDungeonCheckpoint(location) shouldBe false
        world.getBlockAt(0, 69, 0).type = Material.MAGMA_BLOCK
        safeDungeonCheckpoint(location) shouldBe false
        world.getBlockAt(0, 69, 0).type = Material.STONE
        world.getBlockAt(0, 71, 0).type = Material.WATER
        safeDungeonCheckpoint(location) shouldBe false
        world.getBlockAt(0, 71, 0).type = Material.AIR
        safeDungeonCheckpoint(location.clone().apply { yaw = Float.NaN }) shouldBe false
        safeDungeonCheckpoint(location.clone().apply { y = world.minHeight.toDouble() }) shouldBe false
    }
})
