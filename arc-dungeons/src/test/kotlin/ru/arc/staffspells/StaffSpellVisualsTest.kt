package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.abs

class StaffSpellVisualsTest : FreeSpec({
    "overlapping native frames are bounded per viewer and resume without a backlog" {
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(TestTaskScheduler()) {
                val world = mockk<World>(relaxed = true)
                val observer = mockk<Player>(relaxed = true)
                every { observer.uniqueId } returns java.util.UUID.randomUUID()
                every { observer.eyeLocation } returns Location(world, 0.0, 1.62, -5.0)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                var emitted = 0
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers { emitted++ }
                var tick = 0L
                val tasks = LifecycleTaskScope()
                try {
                    val visuals = StaffSpellVisuals(tasks) { tick }
                    val origin = Location(world, 0.0, 1.0, 0.0)
                    val frame = List(192) { StaffParticleSample(Particle.CLOUD, org.bukkit.util.Vector()) }
                    repeat(20) { visuals.particles(origin, frame) }
                    emitted shouldBe 480
                    tick = 2
                    visuals.particles(origin, frame)
                    emitted shouldBe 672
                    tick = 4
                    visuals.particles(origin, List(100) {
                        StaffParticleSample(Particle.CLOUD, org.bukkit.util.Vector(), count = 96)
                    })
                    emitted shouldBe 714 // 42 packets, 4032 particles; the rest are dropped.
                } finally { tasks.close() }
            }
        }
    }

    "positional impact cues reach bounded listeners and preserve their bearing" {
        val scheduler = TestTaskScheduler()
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(scheduler) {
                val world = mockk<World>(relaxed = true)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val distances = listOf(15.0, 40.0, 64.0, 65.0)
                val listeners = distances.associateWith { distance ->
                    mockk<Player>(relaxed = true).also { player ->
                        every { player.eyeLocation } returns Location(world, 0.0, 1.62, -distance)
                    }
                }
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listeners.values.toList()
                val heard = mutableListOf<HeardSound>()
                listeners.values.forEach { player ->
                    every { player.playSound(any<Location>(), any<Sound>(), any<SoundCategory>(),
                        any<Float>(), any<Float>()) } answers {
                        heard += HeardSound(player, firstArg<Location>().clone(), secondArg<Sound>(), arg<Float>(3))
                    }
                }

                val tasks = LifecycleTaskScope()
                try {
                    StaffSpellVisuals(tasks).arcane(Location(world, 0.0, 1.62, 0.0), StaffSpell.PRISM,
                        phase = 2, radius = 2.5)
                } finally {
                    tasks.close()
                }

                heard.size shouldBe 3
                distances.take(3).forEach { actualDistance ->
                    val listener = listeners.getValue(actualDistance)
                    val sound = heard.single { it.listener === listener }
                    val compression = ((actualDistance - 15.0) / (40.0 - 15.0)).coerceIn(0.0, 1.0)
                    val expectedVirtualDistance = 2.5 + compression * 1.5
                    val observedVirtualDistance = sound.position.distance(listener.eyeLocation)
                    (abs(observedVirtualDistance - expectedVirtualDistance) < 1.0e-8) shouldBe true
                    (sound.position.z > listener.eyeLocation.z) shouldBe true
                    val expectedGain = 1.0 + compression * (0.78 - 1.0)
                    (abs(sound.volume - (0.78 * expectedGain).toFloat()) < 1.0e-6f) shouldBe true
                    // Native linear attenuation must still leave a strong cue at gameplay distance.
                    (sound.volume * (1.0 - observedVirtualDistance / 16.0) >= 0.45) shouldBe true
                }
                heard.map { it.sound }.toSet() shouldBe setOf(Sound.BLOCK_GLASS_BREAK)
            }
        }
    }

    "all spell charges and arcane phases use valid particle payloads with quiet travel" {
        val scheduler = TestTaskScheduler()
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(scheduler) {
                val world = mockk<World>(relaxed = true)
                val observer = mockk<Player>(relaxed = true)
                every { observer.eyeLocation } returns Location(world, 0.0, 1.62, -5.0)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                val particles = mutableListOf<Pair<Particle, Any?>>()
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
                    particles += firstArg<Particle>() to null
                }
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<Any>()) } answers {
                    particles += firstArg<Particle>() to arg<Any>(7)
                }
                val heard = mutableListOf<Sound>()
                every { observer.playSound(any<Location>(), any<Sound>(), any<SoundCategory>(),
                    any<Float>(), any<Float>()) } answers { heard += secondArg<Sound>() }

                val tasks = LifecycleTaskScope()
                try {
                    val visuals = StaffSpellVisuals(tasks)
                    val origin = Location(world, 0.0, 1.62, 0.0)
                    StaffSpell.entries.forEach { spell ->
                        visuals.release(origin, spell, charged = true)
                    }
                    val arcaneSpells = listOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
                        StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA)
                    arcaneSpells.forEach { spell ->
                        var particlesBefore = particles.size
                        visuals.arcane(origin, spell, phase = 0, radius = 2.0)
                        (particles.size - particlesBefore) shouldBe 10
                        val beforeTravel = heard.size
                        particlesBefore = particles.size
                        visuals.arcane(origin, spell, phase = 1, radius = 2.0)
                        heard.size shouldBe beforeTravel
                        (particles.size - particlesBefore) shouldBe 4
                        particlesBefore = particles.size
                        visuals.arcane(origin, spell, phase = 2, radius = 2.0)
                        (particles.size - particlesBefore) shouldBe 16
                    }
                    particles.all { (particle, data) ->
                        particle.dataType == Void::class.java || particle.dataType.isInstance(data)
                    } shouldBe true
                } finally {
                    tasks.close()
                }
            }
        }
    }

    "chain bolts add color accents without exceeding the bounded particle budget" {
        val scheduler = TestTaskScheduler()
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(scheduler) {
                val world = mockk<World>(relaxed = true)
                val observer = mockk<Player>(relaxed = true)
                every { observer.eyeLocation } returns Location(world, 0.0, 65.62, 0.0)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                val particles = mutableListOf<Pair<Particle, Any?>>()
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
                    particles += firstArg<Particle>() to null
                }
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<Any>()) } answers {
                    particles += firstArg<Particle>() to arg<Any>(7)
                }
                val tasks = LifecycleTaskScope()
                try {
                    StaffSpellVisuals(tasks).lightning(Location(world, 0.0, 64.0, 0.0),
                        Location(world, 96.0, 64.0, 0.0))
                    scheduler.tick(1)
                } finally {
                    tasks.close()
                }
                (particles.size <= 48) shouldBe true
                particles.any { it.first == Particle.ELECTRIC_SPARK } shouldBe true
                particles.any { it.first == Particle.DUST_COLOR_TRANSITION } shouldBe true
                particles.all { (particle, data) ->
                    particle.dataType == Void::class.java || particle.dataType.isInstance(data)
                } shouldBe true
            }
        }
    }
}) {
    private data class HeardSound(val listener: Player, val position: Location, val sound: Sound, val volume: Float)
}
