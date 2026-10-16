package ru.arc.staffspells

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import io.papermc.paper.math.Position
import java.util.function.Predicate

class StaffSpellCoverTest : FreeSpec({
    "staff block rays preserve Paper partial-shape hits and include collidable passable blocks" {
        val world = loadedWorld()
        val hit = mockk<RayTraceResult>()
        every { hit.hitPosition } returns Vector(1.25, 64.375, 0.5)
        every {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>())
        } returns hit
        val from = Location(world, 0.25, 64.375, 0.5)
        val result = staffBlockHit(from, Vector(1.0, 0.0, 0.0), 3.0)
        result.pathLoaded shouldBe true
        result.hit shouldBe hit
        verify(exactly = 1) {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), 3.0, FluidCollisionMode.NEVER, false)
        }
    }

    "attack rays cross foliage but preserve the next exact wall hit and physical rays" {
        val world = loadedWorld()
        val leaves = mockk<Block> { every { blockData } returns mockk<Leaves>() }
        val wall = mockk<Block> { every { blockData } returns mockk<BlockData>() }
        val leafHit = RayTraceResult(Vector(.8, 64.375, .5))
        val wallHit = RayTraceResult(Vector(1.25, 64.375, .5))
        every { world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(),
            FluidCollisionMode.NEVER, false) } returns leafHit
        every { world.rayTraceBlocks(any<Position>(), any<Vector>(), any<Double>(),
            FluidCollisionMode.NEVER, true, any<Predicate<Block>>()) } answers {
            val filter = arg<Predicate<Block>>(5)
            filter.test(leaves) shouldBe false
            filter.test(wall) shouldBe true
            wallHit
        }
        val from = Location(world, .25, 64.375, .5)
        val targeting = StaffSpellTargeting(staffSettings(), mockk(relaxed = true))
        staffBlockHit(from, Vector(1.0, 0.0, 0.0), 3.0).hit shouldBe leafHit
        (kotlin.math.abs(targeting.rayEnd(from, Vector(1.0, 0.0, 0.0), 3.0).x - 1.21) < 1e-9) shouldBe true
    }

    "an exposed head remains targetable while a solid wall blocks all body samples" {
        val world = loadedWorld()
        val target = mockk<LivingEntity> {
            every { location } answers { Location(world, .5, 64.0, 8.5) }
            every { height } returns 1.8
            every { boundingBox } returns BoundingBox(.2, 64.0, 8.2, .8, 65.8, 8.8)
        }
        var fullWall = false
        every { world.rayTraceBlocks(any<Position>(), any<Vector>(), any<Double>(),
            FluidCollisionMode.NEVER, true, any<Predicate<Block>>()) } answers {
            val end = firstArg<Position>().toVector().add(secondArg<Vector>().clone().multiply(thirdArg<Double>()))
            if (fullWall || end.y < 65.5) RayTraceResult(Vector(.5, 65.1, 4.5)) else null
        }
        val eye = Location(world, .5, 65.62, .5)
        visible(eye, center(target)) shouldBe false
        visible(eye, target) shouldBe true
        val point = requireNotNull(staffVisibleTargetPoint(eye, target))
        (point.y > 65.5 && target.boundingBox.contains(point.toVector())) shouldBe true
        fullWall = true
        visible(eye, target) shouldBe false
    }

    "projectile and observer must share one exposed body point" {
        val world = loadedWorld()
        val target = mockk<LivingEntity> {
            every { location } answers { Location(world, .5, 64.0, 8.5) }
            every { height } returns 1.8
            every { boundingBox } returns BoundingBox(.2, 64.0, 8.2, .8, 65.8, 8.8)
        }
        every { world.rayTraceBlocks(any<Position>(), any<Vector>(), any<Double>(),
            FluidCollisionMode.NEVER, true, any<Predicate<Block>>()) } answers {
            val from = firstArg<Position>()
            val end = from.toVector().add(secondArg<Vector>().clone().multiply(thirdArg<Double>()))
            val blocked = if (from.x() < 0) end.y < 65.5 else end.y >= 65.5
            if (blocked) RayTraceResult(Vector(.5, 65.1, 4.5)) else null
        }
        val left = Location(world, -1.0, 65.62, .5)
        val right = Location(world, 1.0, 65.62, .5)
        visible(left, target) shouldBe true
        visible(right, target) shouldBe true
        staffVisibleTargetPoint(left, target, right) shouldBe null
    }

    "unloaded chunks and invalid rays fail closed before invoking Paper's ray tracer" {
        val world = mockk<World>()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { firstArg<Int>() == 0 }
        val from = Location(world, 15.5, 64.0, 0.5)

        val unloaded = staffBlockHit(from, Vector(1.0, 0.0, 0.0), 2.0)
        unloaded.pathLoaded shouldBe false
        unloaded.hit shouldBe null
        staffBlockHit(from, Vector(), 2.0).pathLoaded shouldBe false

        verify(exactly = 0) {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>())
        }
        verify(exactly = 0) { world.loadChunk(any<Int>(), any<Int>()) }
    }

    "ground lookup uses a partial block hit and rejects unloaded paths" {
        val world = loadedWorld()
        val hit = mockk<RayTraceResult>()
        every { hit.hitPosition } returns Vector(0.5, 62.5, 0.5)
        every {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>())
        } returns hit
        val targeting = StaffSpellTargeting(staffSettings(), mockk(relaxed = true))

        (kotlin.math.abs(requireNotNull(targeting.groundBelow(Location(world, 0.5, 64.0, 0.5), 4.0)).y - 62.54) < 1.0e-9) shouldBe true
        verify(exactly = 1) {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), 4.0, FluidCollisionMode.NEVER, false)
        }

        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
        targeting.groundBelow(Location(world, 15.5, 64.0, 0.5), 4.0) shouldBe null
        verify(exactly = 1) {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), 4.0, FluidCollisionMode.NEVER, false)
        }
    }
})

private fun loadedWorld(): World = mockk<World>().also { world ->
    every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
}
