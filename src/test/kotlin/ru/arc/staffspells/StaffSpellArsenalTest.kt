package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent

class StaffSpellArsenalTest : FreeSpec({
    "new arsenal frames keep native-particle shapes distinct and Display parts bounded" {
        StaffSpellArsenal.SPELLS.forEach { spell ->
            listOf(false, true).forEach { secondary ->
                val duration = staffArsenalLifetime(spell, secondary)
                val parts = staffArsenalParts(spell, 12, duration, 20.0, 3.0, false,
                    secondary, StaffCastInput.of(secondary, false), 1.0)
                (parts.size in 1..48) shouldBe true
                val samples = staffArsenalParticles(spell, 12, duration, 3.0, secondary, 1.0)
                val particleBudget = if (spell == StaffSpell.OSSUARY) 6..14 else 32..192
                (samples.size in particleBudget) shouldBe true
                samples.all { listOf(it.offset.x, it.offset.y, it.offset.z).all(Double::isFinite) } shouldBe true
                parts.all { it.scale.x > 0 && it.scale.y > 0 && it.scale.z > 0 } shouldBe true
                val identities = parts.mapNotNull { it.identity }
                identities.distinct().size shouldBe identities.size
                staffArsenalParticles(spell, duration, duration, 3.0, secondary, 1.0).isEmpty() shouldBe true
            }
        }
        val signatures = StaffSpellArsenal.SPELLS.map { spell ->
            staffArsenalParticles(spell, 12, 40, 3.0, false, 1.0).map { Triple(it.particle, it.offset.x, it.offset.y) }
        }.toSet()
        signatures.size shouldBe StaffSpellArsenal.SPELLS.size
    }

    "redesigned signatures have separate moving and closing silhouettes" {
        val gale = staffArsenalParticles(StaffSpell.GALE, 12, 52, 3.0, false, 1.0)
        val tide = staffArsenalParticles(StaffSpell.TIDE, 12, 44, 1.0, false, 1.0)
        val soul = staffArsenalParticles(StaffSpell.REQUIEM, 12, 46, .9, false, 1.0)
        val galeSignature = gale.map { it.particle }.toSet()
        val tideSignature = tide.map { it.particle }.toSet()
        val soulSignature = soul.map { it.particle }.toSet()
        (galeSignature != tideSignature && tideSignature != soulSignature && galeSignature != soulSignature) shouldBe true

        val rootParts = staffArsenalParts(StaffSpell.NOVA, 30, 48, 12.0, 3.0, false, false,
            StaffCastInput.PRIMARY, 1.0)
        val roots = rootParts.mapNotNull { it.identity }
        roots.any { it.startsWith("root:trail:") } shouldBe true
        roots.any { it.startsWith("root:grasp:") } shouldBe true
        rootParts.filter { it.identity?.startsWith("root:trail:") == true }.all {
            it.center.y - it.scale.y / 2 in 0.02f..0.08f
        } shouldBe true
        rootParts.filter { it.identity?.startsWith("root:thorn:") == true }.all {
            it.center.y - it.scale.y / 2 in 0.02f..0.08f
        } shouldBe true

        val bone = staffArsenalParts(StaffSpell.OSSUARY, 8, 22, 1.0, 1.0, false, false,
            StaffCastInput.PRIMARY, 0.0)
        bone.any { it.material == Material.BONE_BLOCK } shouldBe true
        val earlyBoneTip = staffArsenalParts(StaffSpell.OSSUARY, 2, 54, 1.0, 1.0, false, false,
            StaffCastInput.PRIMARY, 1.0).single { it.identity == "bone:spike:tip" }
        val fullBoneTip = staffArsenalParts(StaffSpell.OSSUARY, 8, 54, 1.0, 1.0, false, false,
            StaffCastInput.PRIMARY, 1.0).single { it.identity == "bone:spike:tip" }
        (fullBoneTip.center.y + fullBoneTip.scale.y / 2 > 3.0f) shouldBe true
        (fullBoneTip.center.y > earlyBoneTip.center.y * 2) shouldBe true

        val telegraph = staffArsenalParts(StaffSpell.OSSUARY, 2, 36, 1.0, 2.0, false, true,
            StaffCastInput.SECONDARY, 1.0)
        val strike = staffArsenalParts(StaffSpell.OSSUARY, OSSUARY_SECONDARY_STRIKE_TICK, 36, 1.0, 2.0, false, true,
            StaffCastInput.SECONDARY, 1.0)
        telegraph.count { it.identity?.startsWith("bone:erupt:") == true } shouldBe 14
        strike.count { it.identity?.startsWith("bone:erupt:") == true } shouldBe 14
        strike.any { it.identity?.startsWith("cage:") == true } shouldBe false
        strike.filter { it.identity?.endsWith(":tip") == true }.all {
            it.center.y + it.scale.y / 2 > 2.5f
        } shouldBe true

        staffArsenalParticles(StaffSpell.OSSUARY, 8, 22, 1.0, false, 1.0).size shouldBe 6
        staffArsenalParticles(StaffSpell.OSSUARY, 4, 36, 2.0, true, 1.0).size shouldBe 6
        staffArsenalParticles(StaffSpell.OSSUARY, 8, 36, 2.0, true, 1.0).size shouldBe 7
        staffArsenalParticles(StaffSpell.OSSUARY, OSSUARY_SECONDARY_STRIKE_TICK,
            36, 2.0, true, 1.0).size shouldBe 14
        staffArsenalParticles(StaffSpell.OSSUARY, OSSUARY_SECONDARY_STRIKE_TICK + 2,
            36, 2.0, true, 1.0).isEmpty() shouldBe true
    }

    "moon blade hits at descent contact and never twice" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("moonfall")
            val player = h.player("moonfall", world, StaffSpell.LUNAR)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 1.0)
            h.scheduler.tick(14)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.scheduler.tick(40)
            h.hits.size shouldBe 1
        }
    }

    "tide orb and soul missiles hit only after their visible flights reach contact" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("tide-orb-flight")
            val player = h.player("tide-orb-flight", world, StaffSpell.TIDE)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(14)
            h.hits shouldBe emptyList()
            h.scheduler.tick(22)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("requiem-missile-flight")
            val player = h.player("requiem-missile-flight", world, StaffSpell.REQUIEM)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(10)
            h.hits shouldBe emptyList()
            h.scheduler.tick(26)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
    }

    "GALE primary and TIDE secondary own display scenes, while the TIDE prison stays at arrival" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-display-scenes")
            val gale = h.player("gale-display-scene", world, StaffSpell.GALE)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(gale, StaffCastInput.PRIMARY, 1.0)
            val galeScene = h.visualPlays.single { it.spell == StaffSpell.GALE && !it.secondary }
            (galeScene.from.distance(gale.location) > 4.0) shouldBe true
        }

        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("tide-wave-display-scene")
            val tide = h.player("tide-wave-display-scene", world, StaffSpell.TIDE)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(tide, StaffCastInput.SECONDARY, 0.0)
            val waveScene = h.visualPlays.single { it.spell == StaffSpell.TIDE && it.secondary }
            h.scheduler.tick(12)
            val moves = h.movedEffects.filter { it.first == waveScene.id }
            (moves.last().second.distance(moves.first().second) > .3) shouldBe true
        }

        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("tide-prison-stage")
            val tide = h.player("tide-prison-stage", world, StaffSpell.TIDE)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(tide, StaffCastInput.PRIMARY, 0.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.TIDE && !it.secondary }
            h.scheduler.tick(36)
            h.movedEffects.count { it.first == scene.id } shouldBe 15
            verify(exactly = 1) { h.effects.impact(scene.id, any(), any(), 16) }
            verify(exactly = 15) { h.effects.synchronize(scene.id, any()) }
        }
    }

    "bone spikes stay visible above the floor and the aimed eruption hits with the spike rise" {
        fun stoneFloor(world: org.bukkit.World) {
            for (x in -2..2) for (z in 0..14) world.getBlockAt(x, 63, z).type = Material.STONE
        }
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("ossuary-spikes")
            stoneFloor(world)
            val player = h.player("ossuary-spikes", world, StaffSpell.OSSUARY)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(14)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            val spikeScenes = h.visualPlays.filter { it.spell == StaffSpell.OSSUARY && !it.secondary }
            spikeScenes.size shouldBe 4
            (spikeScenes.maxOf { it.from.z } - spikeScenes.minOf { it.from.z } > 2.5) shouldBe true
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("ossuary-eruption")
            stoneFloor(world)
            val player = h.player("ossuary-eruption", world, StaffSpell.OSSUARY)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(10)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
        }
    }

    "prism grows before beams reach targets and honors occlusion" {
        withStaffHarness(settings = staffSettings(range = 16.0)) { h ->
            val world = h.paper.addSimpleWorld("prism-growth")
            val player = h.player("prism-growth", world, StaffSpell.PRISM)
            val near = h.zombie(world, x = .5, z = 4.0)
            val far = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(10)
            h.hits shouldBe emptyList()
            h.scheduler.tick(14)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId)
            h.hits.size shouldBe 2
        }
        withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
            val world = h.paper.addSimpleWorld("prism-wall")
            val player = h.player("prism-wall", world, StaffSpell.PRISM)
            h.zombie(world, x = .5, z = 7.0)
            for (x in -2..2) for (y in 63..68) world.getBlockAt(x, y, 3).type = Material.STONE
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 1.0)
            h.scheduler.tick(40)
            h.hits shouldBe emptyList()
        }
    }

    "moving gust, water, and root fronts stop at a one-block ridge while flat fronts reach targets" {
        withStaffHarness { h ->
            fun arena(name: String, spell: StaffSpell, ridge: Boolean, targetZ: Double = 12.0): Pair<org.bukkit.entity.Player, org.bukkit.entity.Zombie> {
                val world = h.paper.addSimpleWorld(name)
                for (x in -1..1) for (z in 0..14) world.getBlockAt(x, 63, z).type = Material.STONE
                if (ridge) world.getBlockAt(0, 64, 5).type = Material.STONE
                val player = h.player("$name-caster", world, spell)
                val target = h.zombie(world, x = .5, z = targetZ)
                return player to target
            }

            val (ridgeGale, ridgeGaleTarget) = arena("gale-ridge", StaffSpell.GALE, ridge = true)
            val (flatGale, flatGaleTarget) = arena("gale-flat", StaffSpell.GALE, ridge = false)
            val (ridgeTide, ridgeTideTarget) = arena("tide-ridge", StaffSpell.TIDE, ridge = true)
            val (flatTide, flatTideTarget) = arena("tide-flat", StaffSpell.TIDE, ridge = false)
            val (ridgeNova, ridgeNovaTarget) = arena("nova-ridge", StaffSpell.NOVA, ridge = true, targetZ = 6.0)
            val (flatNova, flatNovaTarget) = arena("nova-flat", StaffSpell.NOVA, ridge = false, targetZ = 6.0)

            h.controller.start()
            h.controller.cast(ridgeGale, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(flatGale, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(ridgeTide, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(flatTide, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(ridgeNova, StaffCastInput.PRIMARY, 0.0)
            h.controller.cast(flatNova, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(76)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(
                flatGaleTarget.uniqueId, flatTideTarget.uniqueId, flatNovaTarget.uniqueId)
            (h.hits.none { it.uniqueId in setOf(ridgeGaleTarget.uniqueId, ridgeTideTarget.uniqueId, ridgeNovaTarget.uniqueId) }) shouldBe true
        }
    }

    "quit reset and stale callbacks stop the remaining sequence" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-cancel")
            val player = h.player("arsenal-cancel", world, StaffSpell.LUNAR)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(6)
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            val removed = h.removedEffects.size
            h.scheduler.tick(50)
            h.hits shouldBe emptyList()
            h.removedEffects.size shouldBe removed
            (removed > 0) shouldBe true
        }
        var current = true
        withStaffHarness(currentCast = { _, _ -> current }, hit = { _, _ -> current = false; true }) { h ->
            val world = h.paper.addSimpleWorld("moon-stale")
            val player = h.player("moon-stale", world, StaffSpell.LUNAR)
            h.zombie(world, x = .5, z = 5.0)
            h.zombie(world, x = 1.0, z = 5.2)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(40)
            h.hits.size shouldBe 1
        }
    }
})
