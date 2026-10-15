package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.util.UUID

class StaffMagicBarriersTest : FreeSpec({
    afterEach { StaffMagicBarriers.clear() }

    "both faces block staff rays within the displayed width and height but never terrain probes" {
        val world = mockk<World>()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val owner = UUID.randomUUID()
        val id = StaffMagicBarriers.register(owner, Location(world, 0.0, 64.0, 5.0), Vector(0.0, 0.0, 1.0), 2.2, 2.8)
        (id != null) shouldBe true
        StaffMagicBarriers.firstHit(Location(world, 0.0, 65.5, 0.0), Vector(0.0, 0.0, 1.0), 10.0)?.hitPosition shouldBe Vector(0.0, 65.5, 5.0)
        StaffMagicBarriers.firstHit(Location(world, 0.0, 65.5, 10.0), Vector(0.0, 0.0, -1.0), 10.0)?.hitPosition shouldBe Vector(0.0, 65.5, 5.0)
        StaffMagicBarriers.firstHit(Location(world, 2.4, 65.5, 0.0), Vector(0.0, 0.0, 1.0), 10.0) shouldBe null
        StaffMagicBarriers.firstHit(Location(world, 0.0, 67.0, 0.0), Vector(0.0, 0.0, 1.0), 10.0) shouldBe null
        StaffMagicBarriers.firstHit(Location(world, 0.0, 70.0, 5.0), Vector(0.0, -1.0, 0.0), 10.0) shouldBe null
        StaffMagicBarriers.remove(id)
        StaffMagicBarriers.firstHit(Location(world, 0.0, 65.5, 0.0), Vector(0.0, 0.0, 1.0), 10.0) shouldBe null
    }

    "native walls win when nearer and physical movement can ignore magical cover" {
        val world = mockk<World>()
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        val from = Location(world, 0.0, 65.0, 0.0)
        val direction = Vector(0.0, 0.0, 1.0)
        StaffMagicBarriers.register(UUID.randomUUID(), Location(world, 0.0, 64.0, 5.0), direction, 2.0, 2.8)
        var native: RayTraceResult? = RayTraceResult(Vector(0.0, 65.0, 3.0))
        every { world.rayTraceBlocks(any(), any(), any<Double>(), FluidCollisionMode.NEVER, false) } answers { native }
        staffBlockHit(from, direction, 10.0).hit?.hitPosition shouldBe Vector(0.0, 65.0, 3.0)
        native = null
        staffBlockHit(from, direction, 10.0).hit?.hitPosition shouldBe Vector(0.0, 65.0, 5.0)
        staffBlockHit(from, direction, 10.0, includeMagicBarriers = false).hit shouldBe null
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
        staffBlockHit(from, direction, 10.0).pathLoaded shouldBe false
    }
})
