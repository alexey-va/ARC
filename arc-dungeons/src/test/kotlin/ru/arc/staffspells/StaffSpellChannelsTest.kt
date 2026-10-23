package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class StaffSpellChannelsTest : FreeSpec({
    "gravity gathers visible ground on an open field and lands one empowered boulder hit" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gravity-open-field")
            for (x in -1..1) for (z in 0..12) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("gravity-open-field", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 8.0)
            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val tuning = StaffSpellTuning(1.2, 10.0, 44)

            channels.begin(player, StaffSpell.GRAVITY, cast, tuning, secondary = false) shouldBe true
            channels.release(player)
            h.scheduler.tick(40)

            hits shouldBe listOf(target.uniqueId to 13.5)
            verify(exactly = 1) { damage.hit(player, target, cast, tuning.power * 1.35, 13.5) }
            world.getBlockAt(0, 63, 4).type shouldBe Material.STONE
            channels.close()
        }
    }

    "gravity compresses an unlifted grounded target once and draws it toward the caster" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("gravity-low-ceiling")
            for (x in -1..1) for (z in 0..8) {
                world.getBlockAt(x, 63, z).type = Material.STONE
                world.getBlockAt(x, 66, z).type = Material.STONE
            }
            val player = h.player("gravity-low-ceiling", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, .5, 4.5)
            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)

            channels.gravityPulse(player, cast, StaffSpellTuning(1.2, 10.0, 44)) shouldBe true
            target.velocity.z shouldBe -0.35
            // MockBukkit has no entity physics: reproduce a ceiling stopping the launch at its original floor.
            target.velocity = Vector()
            h.scheduler.tick(6)
            hits shouldBe emptyList()
            h.scheduler.tick(2)
            hits shouldBe listOf(target.uniqueId to 10.0)
            h.scheduler.tick(40)
            hits.size shouldBe 1
            channels.close()
        }
    }

    "inferno has a bright bounded stream and spends at most one full-hit budget over sixty ticks" {
        withStaffHarness(settings = staffSettings(range = 24.0)) { h ->
            val clearWorld = h.paper.addSimpleWorld("channel-inferno-clear")
            val blockedWorld = h.paper.addSimpleWorld("channel-inferno-wall")
            val clearPlayer = h.player("inferno-clear", clearWorld, StaffSpell.INFERNO)
            val blockedPlayer = h.player("inferno-wall", blockedWorld, StaffSpell.INFERNO)
            val clearTarget = h.zombie(clearWorld, x = 0.5, z = 4.5)
            val behindWall = h.zombie(blockedWorld, x = 0.5, z = 6.0)
            for (y in 65..66) blockedWorld.getBlockAt(0, y, 3).type = Material.STONE

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings(range = 24.0)
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val tuning = StaffSpellTuning(power = 1.2, vanillaDamage = 10.0, cooldownTicks = 60)
            val clearCast = StaffSpellCast(clearPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val wallCast = StaffSpellCast(blockedPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.begin(clearPlayer, StaffSpell.INFERNO, clearCast, tuning, secondary = false) shouldBe true
            channels.begin(blockedPlayer, StaffSpell.INFERNO, wallCast, tuning, secondary = false) shouldBe true
            verify(exactly = 2) { visuals.particles(any(), any(), any()) }
            for (tick in 1..8) {
                channels.update(clearPlayer, tick) shouldBe true
                channels.update(blockedPlayer, tick) shouldBe true
            }
            hits.map { it.first }.distinct() shouldBe listOf(clearTarget.uniqueId)
            hits.any { it.first == behindWall.uniqueId } shouldBe false

            for (tick in 9..60) {
                val active = channels.update(clearPlayer, tick)
                if (tick < 60) active shouldBe true else active shouldBe false
                val blockedActive = channels.update(blockedPlayer, tick)
                if (tick < 60) blockedActive shouldBe true else blockedActive shouldBe false
            }
            channels.release(clearPlayer)
            (hits.filter { it.first == clearTarget.uniqueId }.sumOf { it.second } in 9.99..10.001) shouldBe true
            val infernoFrame = staffInfernoFrame(8, 8.0)
            infernoFrame.size shouldBe 44
            infernoFrame.count { it.particle == Particle.FLAME } shouldBe 32
            infernoFrame.count { it.particle == Particle.DUST_COLOR_TRANSITION } shouldBe 12
            infernoFrame.filter { it.particle == Particle.DUST_COLOR_TRANSITION }
                .count { (it.data as Particle.DustTransition).size == 0.95f } shouldBe 8
            infernoFrame.filter { it.particle == Particle.DUST_COLOR_TRANSITION }
                .count { (it.data as Particle.DustTransition).size == 0.8f } shouldBe 4
            infernoFrame.filter { it.particle == Particle.FLAME }.maxOf { it.offset.z } shouldBe 8.0
            val clippedInfernoFrame = staffInfernoFrame(8, 0.7)
            clippedInfernoFrame.all { it.offset.z <= 0.7 } shouldBe true
            clippedInfernoFrame.filter { it.particle == Particle.FLAME }.maxOf { it.offset.z } shouldBe 0.7
            clippedInfernoFrame.filter { it.particle == Particle.FLAME }.minOf { it.offset.z } shouldBe 0.465625
            staffInfernoFrame(8, 0.3) shouldBe emptyList()
            staffInfernoFrame(8, 8.0).all { sample ->
                listOf(sample.offset.x, sample.offset.y, sample.offset.z).all(Double::isFinite)
            } shouldBe true
            channels.close()
        }
    }

    "gravity selects without editing blocks, stops at cover, and hits the flat control" {
        withStaffHarness { h ->
            val flatWorld = h.paper.addSimpleWorld("channel-gravity-flat")
            val wallWorld = h.paper.addSimpleWorld("channel-gravity-wall")
            val flatPlayer = h.player("gravity-flat", flatWorld, StaffSpell.GRAVITY)
            val wallPlayer = h.player("gravity-wall", wallWorld, StaffSpell.GRAVITY)
            val flatTarget = h.zombie(flatWorld, x = 0.5, z = 9.2)
            val coveredTarget = h.zombie(wallWorld, x = 0.5, z = 9.2)
            flatWorld.getBlockAt(0, 65, 10).type = Material.STONE
            val cover = wallWorld.getBlockAt(0, 65, 5).apply { type = Material.STONE }

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val tuning = StaffSpellTuning(1.2, 10.0, 44)
            val flatCast = StaffSpellCast(flatPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val wallCast = StaffSpellCast(wallPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.begin(flatPlayer, StaffSpell.GRAVITY, flatCast, tuning, secondary = false) shouldBe true
            channels.begin(wallPlayer, StaffSpell.GRAVITY, wallCast, tuning, secondary = false) shouldBe true
            val flatScene = h.visualPlays.first { it.from.world == flatWorld }.id
            channels.begin(flatPlayer, StaffSpell.GRAVITY, flatCast, tuning, secondary = false) shouldBe false
            channels.update(flatPlayer, 80) shouldBe true
            channels.update(wallPlayer, 80) shouldBe true
            channels.release(flatPlayer)
            channels.release(wallPlayer)
            h.scheduler.tick(40)

            hits.any { it.first == flatTarget.uniqueId } shouldBe true
            hits.any { it.first == coveredTarget.uniqueId } shouldBe false
            val flatMoves = h.projectileMoves.filter { it.id == flatScene }
            (flatMoves[2].at.distance(flatMoves[1].at) >= 3.0) shouldBe true
            (flatMoves.last().at.z - flatMoves.first().at.z > 3.0) shouldBe true
            cover.type shouldBe Material.STONE
            flatWorld.getBlockAt(0, 65, 10).type shouldBe Material.STONE
            channels.close()
        }
    }

    "gravity reaches its 32-block clear-air limit while cover still blocks hits" {
        withStaffHarness { h ->
            val clearWorld = h.paper.addSimpleWorld("channel-gravity-long-clear")
            val coveredWorld = h.paper.addSimpleWorld("channel-gravity-long-wall")
            val clearPlayer = h.player("gravity-long-clear", clearWorld, StaffSpell.GRAVITY)
            val coveredPlayer = h.player("gravity-long-wall", coveredWorld, StaffSpell.GRAVITY)
            val coveredTarget = h.zombie(coveredWorld, x = 0.5, z = 20.0)
            val support = clearWorld.getBlockAt(0, 65, 10).apply { type = Material.STONE }
            val cover = coveredWorld.getBlockAt(0, 65, 5).apply { type = Material.STONE }
            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val tuning = StaffSpellTuning(1.2, 10.0, 44)
            val clearCast = StaffSpellCast(clearPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val coveredCast = StaffSpellCast(coveredPlayer.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.begin(clearPlayer, StaffSpell.GRAVITY, clearCast, tuning, secondary = false) shouldBe true
            channels.begin(coveredPlayer, StaffSpell.GRAVITY, coveredCast, tuning, secondary = false) shouldBe true
            val clearScene = h.visualPlays.first { it.from.world == clearWorld }.id
            support.type = Material.AIR

            channels.release(clearPlayer)
            channels.release(coveredPlayer)
            h.scheduler.tick(24)

            val clearMoves = h.projectileMoves.filter { it.id == clearScene }
            val clearDistance = clearMoves.last().at.distance(clearPlayer.eyeLocation)
            (clearDistance in 31.9..32.01) shouldBe true
            hits.any { it.first == coveredTarget.uniqueId } shouldBe false
            cover.type shouldBe Material.STONE
            channels.close()
        }
    }

    "cancel and world change discard gravity holds without firing a throw" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-cancel")
            val otherWorld = h.paper.addSimpleWorld("channel-gravity-away")
            val player = h.player("gravity-cancel", world, StaffSpell.GRAVITY)
            h.zombie(world, x = 0.5, z = 6.0)
            world.getBlockAt(0, 65, 9).type = Material.STONE
            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val tuning = StaffSpellTuning(1.0, 8.0, 44)
            channels.begin(player, StaffSpell.GRAVITY, cast, tuning, secondary = false) shouldBe true
            channels.cancel(player.uniqueId)
            channels.release(player)
            h.scheduler.tick(8)
            h.projectileMoves shouldBe emptyList()

            val second = h.player("gravity-world-change", world, StaffSpell.GRAVITY)
            val secondCast = StaffSpellCast(second.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.begin(second, StaffSpell.GRAVITY, secondCast, tuning, secondary = false) shouldBe true
            second.teleport(Location(otherWorld, 0.5, 64.0, 0.5).apply { setDirection(Vector(0.0, 0.0, 1.0)) })
            channels.update(second, 2) shouldBe false
            channels.release(second)
            h.scheduler.tick(8)
            hits shouldBe emptyList()
            h.projectileMoves shouldBe emptyList()
            channels.close()
        }
    }

    "gravity remains aimable beyond the old short window and exhaustion cannot release a throw" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-extended-hold")
            val player = h.player("gravity-long-hold", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, x = 0.5, z = 6.2)
            world.getBlockAt(0, 65, 10).type = Material.STONE
            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings()
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val tuning = StaffSpellTuning(1.0, 8.0, 44)
            channels.begin(player, StaffSpell.GRAVITY, cast, tuning, secondary = false) shouldBe true
            channels.update(player, 100) shouldBe true
            channels.update(player, 158) shouldBe true
            channels.update(player, 160) shouldBe false
            channels.release(player)
            h.scheduler.tick(40)

            hits.any { it.first == target.uniqueId } shouldBe false
            channels.close()
        }
    }

    "gravity display fragments keep stable identities and positive scales through impact fade" {
        MockBukkitTestRuntime.open().use {
            val held = staffChannelParts(StaffSpell.GRAVITY, 0, 60, 1.0, 1.0, impact = false)
            val moving = staffChannelParts(StaffSpell.GRAVITY, 20, 60, 1.0, 1.0, impact = false)
            val impact = staffChannelParts(StaffSpell.GRAVITY, 14, 16, 1.0, 2.5, impact = true)

            held.size shouldBe 9
            held.map { it.identity } shouldBe moving.map { it.identity }
            held.map { it.identity } shouldBe impact.map { it.identity }
            (held + moving + impact).all { part ->
                part.identity != null && part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f &&
                    part.material in setOf(Material.STONE, Material.COBBLESTONE, Material.ANDESITE)
            } shouldBe true
            staffChannelParts(StaffSpell.INFERNO, 0, 60, 1.0, 1.0, impact = false) shouldBe emptyList()
        }
    }

    "gravity secondary lifts then slams eligible targets only on ground contact" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-pulse-contact")
            val player = h.player("gravity-pulse", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, x = 0.5, z = 4.5)
            for (x in -1..1) for (z in -1..8) world.getBlockAt(x, 63, z).type = Material.STONE

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings(maxAreaTargets = 3)
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val tuning = StaffSpellTuning(1.2, 10.0, 44)

            channels.gravityPulse(player, cast, tuning) shouldBe true
            h.visualPlays.single().let { scene ->
                scene.spell shouldBe StaffSpell.GRAVITY
                scene.secondary shouldBe true
                scene.impact shouldBe false
            }
            target.velocity.y shouldBe 0.9
            hits shouldBe emptyList()

            // The fixture leaves entity physics to the server; move the target through the observed lift/contact states.
            target.teleport(target.location.clone().add(0.0, 1.0, 0.0))
            h.scheduler.tick(8)
            target.velocity.y shouldBe -0.9
            hits shouldBe emptyList()

            target.teleport(Location(world, target.location.x, 64.0, target.location.z))
            target.velocity = Vector(0.0, -0.9, 0.0)
            h.scheduler.tick(2)
            hits.map { it.first } shouldBe listOf(target.uniqueId)
            verify(exactly = 1) { damage.hit(player, target, cast, tuning.power, tuning.vanillaDamage) }
            target.fallDistance shouldBe 0.0f
            h.scheduler.tick(6)
            hits.count { it.first == target.uniqueId } shouldBe 1
            channels.close()
        }
    }

    "gravity pulse respects cover and radius and cancellation removes its scene" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-pulse-bounds")
            val player = h.player("gravity-pulse-bounds", world, StaffSpell.GRAVITY)
            val covered = h.zombie(world, x = 0.5, z = 5.5)
            val outside = h.zombie(world, x = 0.5, z = 7.5)
            for (x in -1..1) for (z in -1..8) world.getBlockAt(x, 63, z).type = Material.STONE
            for (y in 64..66) world.getBlockAt(0, y, 3).type = Material.STONE

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings(maxAreaTargets = 4)
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            val scene = h.visualPlays
            channels.gravityPulse(player, cast, StaffSpellTuning(1.0, 8.0, 44)) shouldBe true

            covered.velocity.lengthSquared() shouldBe 0.0
            outside.velocity.lengthSquared() shouldBe 0.0
            scene.single().secondary shouldBe true
            channels.cancel(player.uniqueId)
            h.scheduler.tick(8)
            hits shouldBe emptyList()
            h.removedEffects shouldBe listOf(scene.single().id)
            channels.close()
        }
    }

    "gravity pulse suppresses only its target's first fall event across cancellation between timer ticks" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-pulse-fall-guard")
            val player = h.player("gravity-pulse-fall-guard", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, x = 0.5, z = 4.5)
            for (x in -1..1) for (z in -1..8) world.getBlockAt(x, 63, z).type = Material.STONE

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings(maxAreaTargets = 2)
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.gravityPulse(player, cast, StaffSpellTuning(1.0, 8.0, 44)) shouldBe true

            // The timer runs every two ticks, so this event lands between channel updates.
            h.scheduler.tick(1)
            channels.cancel(player.uniqueId)
            h.removedEffects.size shouldBe 1

            val unrelatedDamage = EntityDamageEvent(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0)
            channels.suppressPulseFallDamage(unrelatedDamage) shouldBe false
            unrelatedDamage.isCancelled shouldBe false

            val pulseLanding = EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, 6.0)
            channels.suppressPulseFallDamage(pulseLanding) shouldBe true
            pulseLanding.isCancelled shouldBe true
            target.fallDistance shouldBe 0.0f

            val laterFall = EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, 6.0)
            channels.suppressPulseFallDamage(laterFall) shouldBe false
            laterFall.isCancelled shouldBe false
            channels.close()
        }
    }

    "gravity pulse fall protection expires after sixty ticks without a landing event" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("channel-gravity-pulse-fall-expiry")
            val player = h.player("gravity-pulse-fall-expiry", world, StaffSpell.GRAVITY)
            val target = h.zombie(world, x = 0.5, z = 4.5)
            for (x in -1..1) for (z in -1..8) world.getBlockAt(x, 63, z).type = Material.STONE

            val hits = mutableListOf<Pair<UUID, Double>>()
            val (damage, visuals) = channelDependencies(hits)
            val settings = staffSettings(maxAreaTargets = 2)
            val channels = StaffSpellChannels(settings, damage, h.effects, visuals, StaffSpellTargeting(settings, damage))
            channels.start()
            val cast = StaffSpellCast(player.uniqueId, UUID.randomUUID(), 1, null, null, false)
            channels.gravityPulse(player, cast, StaffSpellTuning(1.0, 8.0, 44)) shouldBe true

            h.scheduler.tick(60)
            val lateFall = EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, 6.0)
            channels.suppressPulseFallDamage(lateFall) shouldBe false
            lateFall.isCancelled shouldBe false
            channels.close()
        }
    }

    "gravity pressure collar converges at floor level around the depressed annulus" {
        MockBukkitTestRuntime.open().use {
            val lift = staffChannelParts(StaffSpell.GRAVITY, 6, 36, 0.1, 6.5, impact = false, secondary = true)
            val shock = staffChannelParts(StaffSpell.GRAVITY, 16, 36, 0.1, 6.5, impact = false, secondary = true)
            val held = staffChannelParts(StaffSpell.GRAVITY, 6, 60, 1.0, 1.0, impact = false)
            val liftFrame = staffGravityPulseFrame(4, 6.5)
            val shockFrame = staffGravityPulseFrame(16, 6.5)

            lift.size shouldBe 16
            lift.map { it.identity } shouldBe shock.map { it.identity }
            lift.map { it.identity }.toSet().intersect(held.mapNotNull { it.identity }.toSet()).isEmpty() shouldBe true
            (lift.maxOf { it.center.y } < .15f) shouldBe true
            (shock.maxOf { it.center.x * it.center.x + it.center.z * it.center.z } <
                lift.maxOf { it.center.x * it.center.x + it.center.z * it.center.z }) shouldBe true
            liftFrame.size shouldBe 24
            shockFrame.isNotEmpty() shouldBe true
            staffGravityPulseFrame(22, 6.5) shouldBe emptyList()
            staffGravityPulseContactFrame().sumOf { it.count } shouldBe 18
            (lift + shock).all { part ->
                part.identity != null && part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f &&
                listOf(part.center.x, part.center.y, part.center.z).all { it.isFinite() }
            } shouldBe true
        }
    }
})

private fun channelDependencies(hits: MutableList<Pair<UUID, Double>>): Pair<StaffSpellDamage, StaffSpellVisuals> {
    val damage = mockk<StaffSpellDamage>()
    every { damage.isCurrent(any(), any()) } returns true
    every { damage.eligible(any(), any(), any()) } answers {
        val target = secondArg<LivingEntity>()
        target !is Player && target.isValid && !target.isDead
    }
    every { damage.hit(any(), any(), any(), any(), any()) } answers {
        hits += secondArg<LivingEntity>().uniqueId to arg<Double>(4)
        true
    }
    val visuals = mockk<StaffSpellVisuals>(relaxed = true)
    every { visuals.withOwner(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    return damage to visuals
}
