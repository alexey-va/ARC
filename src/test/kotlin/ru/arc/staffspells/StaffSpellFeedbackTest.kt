package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.util.UUID
import kotlin.math.sqrt

private data class StaffParticleEvent(val particle: Particle, val location: Location, val data: Any?)

class StaffSpellFeedbackTest : FreeSpec({
    "charge cues play once per stage at the caster's hand and cooldown clicks share a per-player throttle" {
        MockBukkitTestRuntime.open().use {
            var tick = 100L
            Tasks.withScheduler(TestTaskScheduler()) {
                val world = mockk<World>(relaxed = true)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val player = mockk<Player>(relaxed = true)
                val playerId = UUID.randomUUID()
                val eye = Location(world, 0.0, 65.62, 0.0, 0.0f, 0.0f)
                every { player.uniqueId } returns playerId
                every { player.eyeLocation } returns eye
                every { player.world } returns world

                val sounds = mutableListOf<Pair<Sound, Float>>()
                every { player.playSound(any<Location>(), any<Sound>(), any<SoundCategory>(), any<Float>(), any<Float>()) } answers {
                    sounds += secondArg<Sound>() to arg<Float>(4)
                }
                val particles = mutableListOf<StaffParticleEvent>()
                every { player.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
                    particles += StaffParticleEvent(firstArg<Particle>(), secondArg<Location>().clone(), null)
                }
                every { player.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<Any>()) } answers {
                    particles += StaffParticleEvent(firstArg<Particle>(), secondArg<Location>().clone(), arg<Any>(7))
                }

                val tasks = LifecycleTaskScope()
                try {
                    val visuals = StaffSpellVisuals(tasks) { tick }
                    visuals.chargeFeedback(player, StaffSpell.GALE, 0.0)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 0.25)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 0.5)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 0.75)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 1.0)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 1.0)
                    sounds.map { it.first } shouldBe listOf(
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.ENTITY_PLAYER_LEVELUP,
                    )
                    (sounds.slice(1..3).map { it.second }.zipWithNext().all { it.first < it.second }) shouldBe true
                    val firstParticle = particles.first().location
                    (sqrt(firstParticle.distanceSquared(eye)) in 1.2..1.6) shouldBe true
                    (firstParticle.y < eye.y - 0.35) shouldBe true
                    verify(exactly = 0) { world.getNearbyPlayers(any<Location>(), any<Double>()) }

                    visuals.cooldownFeedback(player, StaffSpell.CHAIN)
                    tick += 4
                    visuals.cooldownFeedback(player, StaffSpell.GALE)
                    sounds.size shouldBe 6
                    tick += 4
                    visuals.cooldownFeedback(player, StaffSpell.GALE)
                    sounds.size shouldBe 7

                    visuals.cancelChargeFeedback(playerId)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 0.25)
                    sounds.takeLast(2).map { it.first } shouldBe listOf(
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                    )

                    visuals.cancel(playerId)
                    visuals.cooldownFeedback(player, StaffSpell.GALE)
                    sounds.size shouldBe 10
                    visuals.close()
                    visuals.cooldownFeedback(player, StaffSpell.CHAIN)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 1.0)
                    sounds.takeLast(6).map { it.first } shouldBe listOf(
                        Sound.BLOCK_NOTE_BLOCK_HAT,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.BLOCK_NOTE_BLOCK_CHIME,
                        Sound.ENTITY_PLAYER_LEVELUP,
                    )
                } finally {
                    tasks.close()
                }
            }
        }
    }

    "gale funnel frames stay bounded and alternate quiet wind layers at the three-tick cadence" {
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(TestTaskScheduler()) {
                val world = mockk<World>(relaxed = true)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val observer = mockk<Player>(relaxed = true)
                every { observer.world } returns world
                every { observer.eyeLocation } returns Location(world, 0.0, 65.62, -10.0)
                every { world.getNearbyPlayers(any<Location>(), any<Double>()) } returns listOf(observer)
                val particles = mutableListOf<StaffParticleEvent>()
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>()) } answers {
                    particles += StaffParticleEvent(firstArg<Particle>(), secondArg<Location>().clone(), null)
                }
                every { observer.spawnParticle(any<Particle>(), any<Location>(), any<Int>(),
                    any<Double>(), any<Double>(), any<Double>(), any<Double>(), any<Any>()) } answers {
                    particles += StaffParticleEvent(firstArg<Particle>(), secondArg<Location>().clone(), arg<Any>(7))
                }
                val sounds = mutableListOf<Sound>()
                every { observer.playSound(any<Location>(), any<Sound>(), any<SoundCategory>(), any<Float>(), any<Float>()) } answers {
                    sounds += secondArg<Sound>()
                }

                val tasks = LifecycleTaskScope()
                try {
                    val visuals = StaffSpellVisuals(tasks)
                    val origin = Location(world, 0.0, 64.0, 0.0)
                    visuals.galeFunnel(origin, ageTicks = 21, charge = 1.0)
                    particles.size shouldBe 32
                    particles.count { it.particle == Particle.DUST_COLOR_TRANSITION } shouldBe 24
                    particles.count { it.particle == Particle.CLOUD } shouldBe 8
                    particles.all { (it.data == null && it.particle == Particle.CLOUD) ||
                        (it.data is Particle.DustTransition && it.particle == Particle.DUST_COLOR_TRANSITION) } shouldBe true
                    particles.all { particle ->
                        val dx = particle.location.x - origin.x
                        val dz = particle.location.z - origin.z
                        sqrt(dx * dx + dz * dz) <= 1.10001 && particle.location.y - origin.y in -0.81..3.61
                    } shouldBe true
                    sounds shouldBe listOf(Sound.ITEM_ELYTRA_FLYING)

                    visuals.galeFunnel(origin, ageTicks = 24, charge = 1.0)
                    particles.size shouldBe 64
                    sounds.size shouldBe 1
                    visuals.galeFunnel(origin, ageTicks = 42, charge = 1.0)
                    particles.size shouldBe 96
                    sounds shouldBe listOf(Sound.ITEM_ELYTRA_FLYING, Sound.ENTITY_BREEZE_IDLE_GROUND)
                } finally {
                    tasks.close()
                }
            }
        }
    }
})
