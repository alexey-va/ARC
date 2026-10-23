package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

/** Paper 1.21.11 validates particle payloads before sending a packet, unlike relaxed test players. */
class StaffSpellParticleContractTest : FreeSpec({
    "shared packet emissions preserve authored payloads and count-zero velocity without forcing visibility" {
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(TestTaskScheduler()) {
                val world = mockk<World>(relaxed = true)
                val observer = mockk<Player>(relaxed = true)
                every { observer.uniqueId } returns UUID.randomUUID()
                every { observer.eyeLocation } returns Location(world, 0.0, 1.62, -5.0)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                val received = mutableListOf<ParticleEmission>()
                val particleOwner = mockk<PaperViewerParticles>(relaxed = true)
                every { particleOwner.emit(observer, any<Location>(), any<Particle>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any(), any<Boolean>()) } answers {
                    arg<Boolean>(9) shouldBe false
                    received += ParticleEmission(arg(2), arg(3),
                        Vector(arg<Double>(4), arg<Double>(5), arg<Double>(6)), arg(7), arg(8))
                }
                val tasks = LifecycleTaskScope()
                val visuals = StaffSpellVisuals(tasks, particleOwner)
                try {
                    val origin = Location(world, 0.0, 1.0, 0.0)
                    val transition = Particle.DustTransition(Color.RED, Color.BLUE, 0.8F)
                    visuals.particles(origin, listOf(
                        StaffParticleSample(Particle.CLOUD, Vector(), count = 0,
                            spread = Vector(0.25, -0.5, 0.75), speed = 0.125),
                        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 2,
                            spread = Vector(0.1, 0.2, 0.3), data = transition),
                        StaffParticleSample(Particle.FLASH, Vector()),
                    ))
                    received shouldBe listOf(
                        ParticleEmission(Particle.CLOUD, 0, Vector(0.25, -0.5, 0.75), 0.125, null),
                        ParticleEmission(Particle.DUST_COLOR_TRANSITION, 2, Vector(0.1, 0.2, 0.3), 0.0, transition),
                        ParticleEmission(Particle.FLASH, 1, Vector(), 0.0, Color.WHITE),
                    )

                    listOf(StaffSpell.GALE, StaffSpell.TIDE).forEach { spell ->
                        received.clear()
                        visuals.quickElement(origin, spell, ageTicks = 6, durationTicks = 20, radius = 2.0)
                        received.isNotEmpty() shouldBe true
                        received.all { it.count == 0 && it.speed == 1.0 && it.spread.lengthSquared() > 0.0 } shouldBe true
                    }
                } finally { visuals.close(); tasks.close() }
            }
        }
    }

    "solar and impact particles satisfy the installed Paper payload contract for nearby observers" {
        Particle.FLASH.dataType shouldBe Color::class.java
        val scheduler = TestTaskScheduler()
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(scheduler) {
                val world = mockk<World>(relaxed = true)
                val observer = mockk<Player>(relaxed = true)
                every { observer.eyeLocation } returns Location(world, 8.0, 1.62, 0.0)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                val received = mutableListOf<Pair<Particle, Any?>>()
                val positions = mutableListOf<Pair<Particle, Location>>()
                fun accept(particle: Particle, data: Any?, at: Location) {
                    // This is the platform predicate that stopped the real cast before display creation.
                    require(particle.dataType == Void::class.java || particle.dataType.isInstance(data)) {
                        "${particle.name} requires ${particle.dataType.name}, got ${data?.javaClass?.name}"
                    }
                    received += particle to data
                    positions += particle to at.clone()
                }
                val particleOwner = mockk<PaperViewerParticles>(relaxed = true)
                every { particleOwner.emit(observer, any<Location>(), any<Particle>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any(), false) } answers {
                    accept(arg(2), arg<Any?>(8), arg(1))
                }
                val tasks = LifecycleTaskScope()
                val visuals = StaffSpellVisuals(tasks, particleOwner)
                val eye = Location(world, 0.0, 1.62, 0.0)
                val target = Location(world, 0.0, 1.62, 16.0)
                try {
                    visuals.lance(eye, target)
                    visuals.markBurst(target, 3.5)
                    visuals.markBurst(target, 4.5, blackhole = true)
                    visuals.lightning(eye, target)
                    visuals.lightningTrail(eye, target)
                    visuals.markLaunch(eye, target)
                    visuals.markCharge(target, 0.5)
                    visuals.gravity(target, 4.5, 0.5)
                    visuals.frost(eye, target, 35.0)
                    visuals.wave(eye, target, 5.5, frost = false, directional = true)
                    visuals.wave(eye, target, 5.5, frost = true, directional = false)
                    visuals.nova(target, 8.0)
                    visuals.nova(target, 8.0, secondary = true)
                    visuals.emberTrail(eye, target)
                    visuals.emberBurst(target, 2.8)
                    visuals.emberTrail(eye, target, meteor = true)
                    val icicleStart = received.size
                    visuals.icicleTrail(eye, target)
                    visuals.icicleBurst(target)
                    val icicleParticles = received.drop(icicleStart)
                    icicleParticles.size shouldBe 11
                    icicleParticles.count { it.first == Particle.BLOCK } shouldBe 6
                    val faultStart = received.size
                    visuals.faultCharge(target, 4.5)
                    visuals.faultBurst(target, 4.5)
                    val faultParticles = received.drop(faultStart)
                    (faultParticles.size <= 32) shouldBe true
                    faultParticles.count { it.first == Particle.BLOCK } shouldBe 14
                    faultParticles.filter { it.first == Particle.BLOCK }.all { it.second is BlockData } shouldBe true
                    scheduler.tick(40)
                    (received.count { it.first == Particle.FLASH } >= 5) shouldBe true
                    received.filter { it.first == Particle.FLASH }.all { it.second is Color } shouldBe true

                    val wakeStart = positions.size
                    visuals.emberTrail(Location(world, 0.0, 1.62, 16.0), Location(world, 0.0, 1.62, 18.4))
                    positions.drop(wakeStart).size shouldBe 3
                    positions.drop(wakeStart).all { it.second.z < 16.0 } shouldBe true
                    val fallingWakeStart = positions.size
                    visuals.emberTrail(Location(world, 0.0, 16.0, 16.0), Location(world, 0.0, 13.6, 16.0), meteor = true)
                    positions.drop(fallingWakeStart).size shouldBe 3
                    positions.drop(fallingWakeStart).all { it.second.y > 16.0 } shouldBe true

                    val meteorBurstStart = received.size
                    visuals.emberBurst(target, 2.8, meteor = true)
                    scheduler.tick(20)
                    val meteorBurst = received.drop(meteorBurstStart)
                    meteorBurst.size shouldBe 206
                    meteorBurst.count { it.first == Particle.BLOCK } shouldBe 14
                    meteorBurst.filter { it.first == Particle.BLOCK }.all { it.second is BlockData } shouldBe true

                    val owner = UUID.randomUUID()
                    val staleStart = received.size
                    visuals.withOwner(owner) { visuals.nova(target, 8.0) }
                    visuals.cancel(owner)
                    verify(exactly = 1) { particleOwner.invalidatePending() }
                    scheduler.tick(20)
                    received.size shouldBe staleStart
                } finally { visuals.close(); tasks.close() }
                verify(exactly = 1) { particleOwner.close() }
            }
        }
    }
})

private data class ParticleEmission(
    val particle: Particle,
    val count: Int,
    val spread: Vector,
    val speed: Double,
    val data: Any?,
)
