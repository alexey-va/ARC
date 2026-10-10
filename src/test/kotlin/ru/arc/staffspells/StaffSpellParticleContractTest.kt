package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

/** Paper 1.21.11 validates particle payloads before sending a packet, unlike relaxed test players. */
class StaffSpellParticleContractTest : FreeSpec({
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
                fun accept(particle: Particle, data: Any?) {
                    // This is the platform predicate that stopped the real cast before display creation.
                    require(particle.dataType == Void::class.java || particle.dataType.isInstance(data)) {
                        "${particle.name} requires ${particle.dataType.name}, got ${data?.javaClass?.name}"
                    }
                    received += particle to data
                }
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
                    accept(firstArg(), null)
                }
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<Any>()) } answers {
                    accept(firstArg(), arg<Any>(7))
                }
                val tasks = LifecycleTaskScope()
                val visuals = StaffSpellVisuals(tasks)
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
                    scheduler.tick(40)
                    (received.count { it.first == Particle.FLASH } >= 5) shouldBe true
                    received.filter { it.first == Particle.FLASH }.all { it.second is Color } shouldBe true

                    val meteorBurstStart = received.size
                    visuals.emberBurst(target, 2.8, meteor = true)
                    scheduler.tick(20)
                    val meteorBurst = received.drop(meteorBurstStart)
                    meteorBurst.size shouldBe 206
                    meteorBurst.count { it.first == Particle.BLOCK } shouldBe 14
                    meteorBurst.filter { it.first == Particle.BLOCK }.all { it.second is BlockData } shouldBe true
                } finally { tasks.close() }
            }
        }
    }
})
