package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent

class StaffSpellArsenalTest : FreeSpec({
    "new sequences have rich native silhouettes and only physical subjects use displays" {
        val pure = setOf(StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM)
        StaffSpellArsenal.SPELLS.forEach { spell ->
            listOf(false, true).forEach { secondary ->
                val duration = staffArsenalLifetime(spell, secondary)
                val parts = staffArsenalParts(spell, 12, duration, 20.0, 3.0, false,
                    secondary, StaffCastInput.of(secondary, false), 1.0)
                if (spell in pure) parts.isEmpty() shouldBe true else (parts.size in 1..40) shouldBe true
                val samples = staffArsenalParticles(spell, 12, duration, 3.0, secondary, 1.0)
                (samples.size in 32..192) shouldBe true
                samples.all { listOf(it.offset.x, it.offset.y, it.offset.z).all(Double::isFinite) } shouldBe true
                parts.all { it.scale.x > 0 && it.scale.y > 0 && it.scale.z > 0 } shouldBe true
                staffArsenalParticles(spell, duration, duration, 3.0, secondary, 1.0).isEmpty() shouldBe true
            }
        }
        val signatures = StaffSpellArsenal.SPELLS.map { spell ->
            staffArsenalParticles(spell, 12, 40, 3.0, false, 1.0).map { Triple(it.particle, it.offset.x, it.offset.y) }
        }.toSet()
        signatures.size shouldBe StaffSpellArsenal.SPELLS.size
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

    "moving ground waves stop at a one-block ridge while flat waves still reach targets" {
        withStaffHarness { h ->
            fun arena(name: String, spell: StaffSpell, ridge: Boolean): Pair<org.bukkit.entity.Player, org.bukkit.entity.Zombie> {
                val world = h.paper.addSimpleWorld(name)
                for (x in -1..1) for (z in 0..14) world.getBlockAt(x, 63, z).type = Material.STONE
                if (ridge) world.getBlockAt(0, 64, 5).type = Material.STONE
                val player = h.player("$name-caster", world, spell)
                val target = h.zombie(world, x = .5, z = 12.0)
                return player to target
            }

            val (ridgeGale, ridgeGaleTarget) = arena("gale-ridge", StaffSpell.GALE, ridge = true)
            val (flatGale, flatGaleTarget) = arena("gale-flat", StaffSpell.GALE, ridge = false)
            val (ridgeTide, ridgeTideTarget) = arena("tide-ridge", StaffSpell.TIDE, ridge = true)
            val (flatTide, flatTideTarget) = arena("tide-flat", StaffSpell.TIDE, ridge = false)

            h.controller.start()
            h.controller.cast(ridgeGale, StaffCastInput.PRIMARY, 0.0)
            h.controller.cast(flatGale, StaffCastInput.PRIMARY, 0.0)
            h.controller.cast(ridgeTide, StaffCastInput.SECONDARY, 0.0)
            h.controller.cast(flatTide, StaffCastInput.SECONDARY, 0.0)
            h.scheduler.tick(76)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(flatGaleTarget.uniqueId, flatTideTarget.uniqueId)
            (h.hits.none { it.uniqueId == ridgeGaleTarget.uniqueId || it.uniqueId == ridgeTideTarget.uniqueId }) shouldBe true
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
