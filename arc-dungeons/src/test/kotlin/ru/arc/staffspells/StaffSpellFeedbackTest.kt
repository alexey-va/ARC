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
import org.joml.Vector3f
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
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_SLIDE,
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
                        Sound.ENTITY_BREEZE_WIND_BURST,
                    )

                    visuals.cancel(playerId)
                    visuals.cooldownFeedback(player, StaffSpell.GALE)
                    sounds.size shouldBe 10
                    listOf(StaffSpell.INFERNO, StaffSpell.GRAVITY, StaffSpell.OSSUARY).forEach { spell ->
                        tick += 8
                        val previousSounds = sounds.size
                        val previousParticles = particles.size
                        visuals.cooldownFeedback(player, spell)
                        sounds.size shouldBe previousSounds + 1
                        (particles.size > previousParticles) shouldBe true
                    }
                    visuals.close()
                    visuals.cooldownFeedback(player, StaffSpell.CHAIN)
                    visuals.chargeFeedback(player, StaffSpell.GALE, 1.0)
                    sounds.takeLast(6).map { it.first } shouldBe listOf(
                        Sound.BLOCK_AMETHYST_CLUSTER_HIT,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_WIND_BURST,
                        Sound.ENTITY_BREEZE_SLIDE,
                    )
                    listOf(
                        StaffSpell.EMBER to listOf(Sound.BLOCK_FIRE_AMBIENT, Sound.ITEM_FIRECHARGE_USE,
                            Sound.ITEM_FIRECHARGE_USE, Sound.ITEM_FIRECHARGE_USE, Sound.ENTITY_BLAZE_SHOOT),
                        StaffSpell.ICICLE to listOf(Sound.BLOCK_AMETHYST_BLOCK_CHIME, Sound.BLOCK_AMETHYST_CLUSTER_HIT,
                            Sound.BLOCK_AMETHYST_CLUSTER_HIT, Sound.BLOCK_AMETHYST_CLUSTER_HIT,
                            Sound.BLOCK_AMETHYST_BLOCK_RESONATE),
                    ).forEach { (spell, expected) ->
                        visuals.cancelChargeFeedback(playerId)
                        val before = sounds.size
                        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { progress ->
                            repeat(3) { visuals.chargeFeedback(player, spell, progress) }
                        }
                        sounds.drop(before).map { it.first } shouldBe expected
                    }
                } finally {
                    tasks.close()
                }
            }
        }
    }

    "ember and icicle charge particles reach their transformed held shapes outside the camera" {
        MockBukkitTestRuntime.open().use {
            Tasks.withScheduler(TestTaskScheduler()) {
                val world = mockk<World>(relaxed = true)
                every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
                val player = mockk<Player>(relaxed = true)
                val playerId = UUID.randomUUID()
                val eye = Location(world, 0.0, 65.62, 0.0, 35.0f, 12.0f)
                every { player.uniqueId } returns playerId
                every { player.eyeLocation } returns eye
                every { player.world } returns world
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
                    val visuals = StaffSpellVisuals(tasks)
                    visuals.chargeFeedback(player, StaffSpell.EMBER, 0.5)
                    val direction = eye.direction
                    val emberRotation = staffChargeOrientation(StaffSpell.EMBER, eye.yaw,
                        Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
                    val emberCore = staffEmberBodyParts(0.5, staffEmberChargeOffset())
                        .first { it.identity == "ember:5:hotspot" }
                    val emberTargetOffset = emberRotation.transform(Vector3f(emberCore.center))
                    val emberTarget = eye.clone().add(emberTargetOffset.x.toDouble(),
                        emberTargetOffset.y.toDouble(), emberTargetOffset.z.toDouble())
                    particles.any { it.location.distanceSquared(emberTarget) < 0.0001 } shouldBe true
                    particles.all { it.location.distanceSquared(eye) >= 1.5 * 1.5 } shouldBe true

                    val beforeIcicle = particles.size
                    visuals.chargeFeedback(player, StaffSpell.ICICLE, 0.5)
                    val icicleRotation = staffChargeOrientation(StaffSpell.ICICLE, eye.yaw,
                        Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
                    val icicleTargets = listOf(0, 5, 10).map { slot ->
                        val core = staffIcicleChargeParts(0.5, slot).first()
                        val facingSurface = core.rotation.transform(Vector3f(0f, 0f, core.scale.z * 0.45f))
                        val localTarget = Vector3f(staffIcicleChargeAnchorOffset(slot))
                            .add(core.center).add(facingSurface)
                        val offset = icicleRotation.transform(localTarget)
                        eye.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                    }
                    val icicleParticles = particles.drop(beforeIcicle)
                    icicleTargets.all { target ->
                        icicleParticles.any { it.location.distanceSquared(target) < 0.0001 }
                    } shouldBe true
                    icicleParticles.all { it.location.distanceSquared(eye) >= 1.5 * 1.5 } shouldBe true
                    visuals.close()
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
