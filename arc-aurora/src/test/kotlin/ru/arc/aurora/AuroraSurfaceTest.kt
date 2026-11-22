package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.Snow
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

class AuroraSurfaceTest : FreeSpec({
    "loaded support uses visible snow, source water and sand heights" {
        val world = mockk<World>()
        val block = mockk<Block>()
        every { world.isChunkLoaded(-1, 0) } returns true
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.getHighestBlockYAt(-1, 0, HeightMap.WORLD_SURFACE) } returns 63
        every { world.getBlockAt(-1, 63, 0) } returns block

        every { block.type } returns Material.SNOW
        val snow = mockk<Snow>()
        every { snow.layers } returns 3
        every { block.blockData } returns snow
        terrainSurfaceAt(world, AuroraShape.INVISIBLE_HARE, -1, 0) shouldBe AuroraSurface(63, 63.375)
        every { block.type } returns Material.SNOW_BLOCK
        terrainSurfaceAt(world, AuroraShape.INVISIBLE_HARE, -1, 0) shouldBe AuroraSurface(63, 64.0)
        every { block.type } returns Material.POWDER_SNOW
        terrainSurfaceAt(world, AuroraShape.INVISIBLE_HARE, -1, 0) shouldBe null

        every { block.type } returns Material.WATER
        val water = mockk<Levelled>()
        val overhead = mockk<Block>()
        every { water.level } returns 0
        every { block.blockData } returns water
        every { world.getBlockAt(-1, 64, 0) } returns overhead
        every { overhead.type } returns Material.AIR
        terrainSurfaceAt(world, AuroraShape.PAPER_FLOTILLA, -1, 0) shouldBe AuroraSurface(63, 63 + 8.0 / 9.0)
        every { overhead.type } returns Material.STONE
        terrainSurfaceAt(world, AuroraShape.PAPER_FLOTILLA, -1, 0, waterline = 63) shouldBe null
        every { water.level } returns 4
        terrainSurfaceAt(world, AuroraShape.PAPER_FLOTILLA, -1, 0) shouldBe null

        every { block.type } returns Material.RED_SAND
        terrainSurfaceAt(world, AuroraShape.TUMBLEWEED, -1, 0) shouldBe AuroraSurface(63, 64.0)
        every { block.type } returns Material.CACTUS
        terrainSurfaceAt(world, AuroraShape.TUMBLEWEED, -1, 0) shouldBe null
    }

    "unloaded terrain does not query the heightmap or block" {
        val world = mockk<World>()
        every { world.isChunkLoaded(-2, 1) } returns false
        terrainSurfaceAt(world, AuroraShape.PAPER_FLOTILLA, -17, 31) shouldBe null
        verify(exactly = 0) { world.getHighestBlockYAt(any<Int>(), any<Int>(), any<HeightMap>()) }
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
    }

    "surface candidates are denser without changing ordinary airborne seeds" {
        val settings = AuroraSkyFieldSettings(cellSize = 40.0, jitter = 0.0, viewDistance = 48.0, cullHysteresis = 0.0)
        val world = UUID(0, 1)
        val sky = AuroraConfig.parse(YamlConfiguration().apply { set("profiles.sky.sky-scene", true) }).profiles.single()
        val sand = sky.copy(id = "sand", shape = AuroraShape.TUMBLEWEED, radius = 48.0)
        val airborne = AuroraWorldField.candidates(world, listOf(sky), 0.0, 0.0, 0L, settings)
        val mixed = AuroraWorldField.candidates(world, listOf(sky, sand), 0.0, 0.0, 0L, settings, terrainScenes = true)
        mixed.filterNot { it.cell.terrain } shouldBe airborne
        (mixed.count { it.cell.terrain } > airborne.size) shouldBe true
    }
})
