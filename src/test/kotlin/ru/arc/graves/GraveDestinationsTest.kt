package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class GraveDestinationsTest : FunSpec({
    test("death placement retains the anchor while visits choose an adjacent column") {
        withDestinationRuntime {
        val world = destinationWorld()
        val anchor = GraveDestinations.nearby(world, 0, 64, 0)!!
        anchor.blockX shouldBe 0
        anchor.blockZ shouldBe 0
        val visit = GraveDestinations.visit(world, 0, 64, 0)!!
        (visit.blockX != 0 || visit.blockZ != 0) shouldBe true
        GraveDestinations.safe(visit) shouldBe true
        }
    }

    test("adjacent visits skip unloaded chunks without reading or generating their blocks") {
        withDestinationRuntime {
        val world = destinationWorld()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { firstArg<Int>() == 0 && secondArg<Int>() == 0 }

        val visit = GraveDestinations.visit(world, 0, 64, 0)!!

        visit.blockX shouldBe 0
        visit.blockZ shouldBe 1
        verify(exactly = 0) { world.getBlockAt(-1, any<Int>(), any<Int>()) }
        verify(exactly = 0) { world.getBlockAt(any<Int>(), any<Int>(), -1) }
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }
        }
    }

    test("a visit declines unsafe adjacent terrain rather than falling back inside the tomb") {
        withDestinationRuntime {
        val world = destinationWorld()
        every { world.worldBorder.isInside(any<Location>()) } answers {
            firstArg<Location>().let { it.blockX == 0 && it.blockZ == 0 }
        }
        GraveDestinations.nearby(world, 0, 64, 0)!!.blockX shouldBe 0
        GraveDestinations.visit(world, 0, 64, 0) shouldBe null
        }
    }

    test("neighbouring tomb columns are excluded and dense tombs decline the visit") {
        withDestinationRuntime {
            val world = destinationWorld()
            val neighbour = setOf(-1 to 0)
            val visit = GraveDestinations.visit(world, 0, 64, 0, neighbour)!!
            (visit.blockX to visit.blockZ) shouldBe (0 to -1)
            val dense = (-2..2).flatMap { x -> (-2..2).map { z -> x to z } }.toSet()
            GraveDestinations.visit(world, 0, 64, 0, dense) shouldBe null
        }
    }
})

private fun withDestinationRuntime(test: () -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { test() }
}

private fun destinationWorld(): World {
    val world = mockk<World>(relaxed = true)
    val border = mockk<WorldBorder>()
    every { border.isInside(any<Location>()) } returns true
    every { world.worldBorder } returns border
    every { world.minHeight } returns -64
    every { world.maxHeight } returns 320
    every { world.environment } returns World.Environment.NORMAL
    every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
    every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
        val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
        mockk<Block> {
            every { type } returns if (y == 63) Material.STONE else Material.AIR
            every { isPassable } returns (y != 63)
            every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
        }
    }
    return world
}
