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
                val particleBudget = when (spell) {
                    StaffSpell.OSSUARY -> 6..14
                    // Water/wind silhouettes are displays; native particles only accent their surfaces.
                    StaffSpell.TIDE, StaffSpell.GALE -> 1..64
                    else -> 32..192
                }
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

    "moon blade alternate hits at descent contact and never twice" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("moonfall")
            val player = h.player("moonfall", world, StaffSpell.LUNAR)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 1.0)
            h.scheduler.tick(14)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.scheduler.tick(40)
            h.hits.size shouldBe 1
        }
    }

    "TIDE alternate wave crosses both swept entity contacts once" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("tide-orb-contact")
            val player = h.player("tide-orb-contact", world, StaffSpell.TIDE)
            val first = h.zombie(world, x = .5, z = 8.0)
            val nearby = h.zombie(world, x = .5, z = 8.9)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.TIDE && it.secondary }
            h.scheduler.tick(8)
            h.hits shouldBe emptyList()
            h.scheduler.tick(32)
            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(first.uniqueId, nearby.uniqueId)
            h.hits.count { it.uniqueId == first.uniqueId } shouldBe 1
            h.hits.count { it.uniqueId == nearby.uniqueId } shouldBe 1
            h.scheduler.tick(20)
            h.hits.size shouldBe 2
            (h.movedEffects.filter { it.first == scene.id }.last().second.distance(scene.from) > 1.0) shouldBe true
            verify(exactly = 0) { h.effects.impact(any(), any(), any(), any()) }
        }
    }

    "TIDE alternate wave is clipped by cover and ends at its charged range" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("tide-wave-wall")
            for (x in -1..1) for (z in 0..40) world.getBlockAt(x, 63, z).type = Material.STONE
            for (x in -1..1) for (y in 64..68) world.getBlockAt(x, y, 8).type = Material.STONE
            val player = h.player("tide-wave-wall", world, StaffSpell.TIDE)
            h.zombie(world, x = .5, z = 12.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 1.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.TIDE && it.secondary }
            (scene.to.z < 8.0 && scene.to.z > 5.0) shouldBe true
            h.scheduler.tick(60)
            h.hits shouldBe emptyList()
            verify(exactly = 0) { h.effects.impact(any(), any(), any(), any()) }
        }

        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            val world = h.paper.addSimpleWorld("tide-wave-range")
            for (x in -1..1) for (z in 0..40) world.getBlockAt(x, 63, z).type = Material.STONE
            val player = h.player("tide-wave-range", world, StaffSpell.TIDE)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 1.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.TIDE && it.secondary }
            (kotlin.math.abs(scene.to.z - player.location.z - 36.0) < 0.05) shouldBe true
            h.scheduler.tick(60)
            h.hits shouldBe emptyList()
        }
    }

    "requiem bell ring hits only after its clang timing" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("requiem-bell-flight")
            val player = h.player("requiem-bell-flight", world, StaffSpell.REQUIEM)
            val target = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(16)
            h.hits shouldBe emptyList()
            h.scheduler.tick(2)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.projectileMoves shouldBe emptyList()
        }
    }

    "requiem secondary rings hit side and rear targets once per clang and stop at cover" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("requiem-nova")
            val player = h.player("requiem-nova", world, StaffSpell.REQUIEM)
            val side = h.zombie(world, x = 4.5, z = .5)
            val rear = h.zombie(world, x = .5, z = -4.5)
            val covered = h.zombie(world, x = .5, z = 4.5)
            for (x in -1..1) for (y in 64..68) world.getBlockAt(x, y, 2).type = Material.STONE
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.REQUIEM && it.secondary }
            (scene.from.distance(player.location) < .1) shouldBe true
            h.scheduler.tick(20)
            h.hits shouldBe emptyList()
            h.scheduler.tick(46)
            h.hits.count { it.uniqueId == side.uniqueId } shouldBe 3
            h.hits.count { it.uniqueId == rear.uniqueId } shouldBe 3
            h.hits.any { it.uniqueId == covered.uniqueId } shouldBe false
            h.damageScales.size shouldBe 6
            h.damageScales.all { (power, vanilla) -> kotlin.math.abs(power - .45) < .001 &&
                kotlin.math.abs(vanilla - 2.7) < .001 } shouldBe true
        }
    }

    "GALE and TIDE alternate fronts own moving scenes through their travel" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-display-scenes")
            val gale = h.player("gale-display-scene", world, StaffSpell.GALE)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(gale, StaffCastInput.SECONDARY, 1.0)
            val galeScene = h.visualPlays.single { it.spell == StaffSpell.GALE && it.secondary }
            (galeScene.to.distance(galeScene.from) > 30.0) shouldBe true
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
            val world = h.paper.addSimpleWorld("tide-splash-stage")
            val tide = h.player("tide-splash-stage", world, StaffSpell.TIDE)
            h.controller.start()
            h.controller.cast(tide, StaffCastInput.SECONDARY, 0.0)
            val scene = h.visualPlays.single { it.spell == StaffSpell.TIDE && it.secondary }
            h.scheduler.tick(36)
            h.movedEffects.count { it.first == scene.id } shouldBe 19
            (scene.id in h.removedEffects) shouldBe false
            verify(exactly = 19) { h.effects.synchronize(scene.id, any()) }
            h.scheduler.tick(8)
            (scene.id in h.removedEffects) shouldBe true
        }
    }

    "Ossuary primary uses delayed close contact while its alternate eruption rises from the floor" {
        fun stoneFloor(world: org.bukkit.World) {
            for (x in -2..2) for (z in 0..14) world.getBlockAt(x, 63, z).type = Material.STONE
        }
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("ossuary-spikes")
            stoneFloor(world)
            val player = h.player("ossuary-spikes", world, StaffSpell.OSSUARY)
            val target = h.zombie(world, x = .5, z = 4.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(3)
            h.hits shouldBe emptyList()
            h.scheduler.tick(1)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.scheduler.tick(42)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.visualPlays.none { it.spell == StaffSpell.OSSUARY } shouldBe true
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

    "Prism alternate beams grow before focus contact and honor occlusion" {
        withStaffHarness(settings = staffSettings(range = 16.0)) { h ->
            val world = h.paper.addSimpleWorld("prism-growth")
            val player = h.player("prism-growth", world, StaffSpell.PRISM)
            val near = h.zombie(world, x = .5, z = 4.0)
            val far = h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(10)
            h.hits shouldBe emptyList()
            h.scheduler.tick(14)
            h.hits.map { it.uniqueId }.distinct() shouldBe listOf(near.uniqueId)
            (far in h.hits) shouldBe false
            h.scheduler.tick(30)
            h.hits.size shouldBe 1
        }
        withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
            val world = h.paper.addSimpleWorld("prism-wall")
            val player = h.player("prism-wall", world, StaffSpell.PRISM)
            h.zombie(world, x = .5, z = 7.0)
            for (x in -2..2) for (y in 63..68) world.getBlockAt(x, y, 3).type = Material.STONE
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 1.0)
            h.scheduler.tick(40)
            h.hits shouldBe emptyList()
        }
    }

    "moving Gale and TIDE alternate fronts stop at a one-block ridge while flat fronts reach targets" {
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
            h.controller.start()
            h.controller.cast(ridgeGale, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(flatGale, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(ridgeTide, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(flatTide, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(76)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(
                flatGaleTarget.uniqueId, flatTideTarget.uniqueId)
            (h.hits.none { it.uniqueId in setOf(ridgeGaleTarget.uniqueId, ridgeTideTarget.uniqueId) }) shouldBe true
        }
    }

    "Gale alternate keeps its fixed horizontal reach while close primaries ignore aim and charge" {
        withStaffHarness(settings = staffSettings(range = 48.0)) { h ->
            h.controller.start()
            listOf(StaffSpell.GALE, StaffSpell.OSSUARY, StaffSpell.TIDE).forEach { spell ->
                listOf(0.0, 1.0).forEach { charge ->
                    listOf(-65f, 65f).forEach { pitch ->
                        val world = h.paper.addSimpleWorld("fixed-${spell.id}-$charge-$pitch")
                        for (x in -1..1) for (z in 0..40) world.getBlockAt(x, 63, z).type = Material.STONE
                        val player = h.player("${spell.id}-$charge-$pitch", world, spell)
                        player.teleport(player.location.apply { this.pitch = pitch })
                        val near = h.zombie(world, .5, 4.0)
                        val far = h.zombie(world, .5, if (spell == StaffSpell.GALE) 32.0 else 8.0)
                        h.controller.cast(player, if (spell == StaffSpell.GALE) StaffCastInput.SECONDARY
                            else StaffCastInput.PRIMARY, charge)
                        h.scheduler.tick(54)
                        if (spell == StaffSpell.GALE) {
                            (near in h.hits) shouldBe true
                            (far in h.hits) shouldBe true
                        } else {
                            (near in h.hits) shouldBe true
                            (far in h.hits) shouldBe false
                        }
                        val scenes = h.visualPlays.filter { it.from.world == world }
                        if (spell == StaffSpell.GALE) {
                            val gust = scenes.single()
                            (kotlin.math.abs(gust.to.z - player.location.z - 36.0) < .01) shouldBe true
                            (kotlin.math.abs(gust.to.y - gust.from.y) < .01) shouldBe true
                        } else {
                            scenes shouldBe emptyList()
                        }
                    }
                }
            }
        }
    }

    "quit reset and stale callbacks stop the remaining sequence" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-cancel")
            val player = h.player("arsenal-cancel", world, StaffSpell.REQUIEM)
            h.zombie(world, x = .5, z = 8.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
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
            h.controller.cast(player, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(40)
            h.hits.size shouldBe 1
        }
    }
})
