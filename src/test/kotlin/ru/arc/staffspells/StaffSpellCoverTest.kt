package ru.arc.staffspells

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector

class StaffSpellCoverTest : FreeSpec({
    "staff block rays preserve Paper partial-shape hits and include collidable passable blocks" {
        val world = loadedWorld()
        val hit = mockk<RayTraceResult>()
        every { hit.hitPosition } returns Vector(1.25, 64.375, 0.5)
        every {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), any<FluidCollisionMode>(), any<Boolean>())
        } returns hit
        val from = Location(world, 0.25, 64.375, 0.5)
        val targeting = StaffSpellTargeting(staffSettings(), mockk(relaxed = true))

        val result = staffBlockHit(from, Vector(1.0, 0.0, 0.0), 3.0)
        result.pathLoaded shouldBe true
        result.hit shouldBe hit
        (kotlin.math.abs(targeting.rayEnd(from, Vector(1.0, 0.0, 0.0), 3.0).x - 1.21) < 1.0e-9) shouldBe true

        verify(exactly = 2) {
            world.rayTraceBlocks(any<Location>(), any<Vector>(), 3.0, FluidCollisionMode.NEVER, false)
        }
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
