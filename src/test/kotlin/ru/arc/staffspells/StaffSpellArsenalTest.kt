package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent

class StaffSpellArsenalTest : FreeSpec({
    "arsenal contact controls differ from projectiles and keep bounded finite geometry" {
        val inputs = StaffCastInput.entries
        listOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM,
            StaffSpell.PRISM, StaffSpell.NOVA).forEach { spell ->
            val states = inputs.map { input ->
                val parts = staffArsenalParts(spell, 5, 20, 12.0, 3.0, false,
                    input.secondary, input)
                if (staffParticleOnlyQuick(spell, input)) parts.isEmpty() shouldBe true
                else (parts.size in 1..48) shouldBe true
                parts.all { part ->
                    part.center.x.isFinite() && part.center.y.isFinite() && part.center.z.isFinite() &&
                        part.scale.x.isFinite() && part.scale.y.isFinite() && part.scale.z.isFinite()
                } shouldBe true
                parts.filter { it.visible }.map { part ->
                    listOf(part.material, part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z)
                }
            }
                        (states[2] != states[0]) shouldBe true
            (states[3] != states[0]) shouldBe true
            (states[2] != states[3]) shouldBe true
        }
        staffArsenalFront(5, 10, 4.0) shouldBe 2.0
        staffArsenalFront(20, 10, 4.0) shouldBe 4.0
    }

    "shift contact attack selects an aimed enemy inside its maximum reach" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("contact-near-aim")
            val player = h.player("contact-near-aim", world, StaffSpell.PRISM)
            val target = h.zombie(world, x = 0.5, z = 5.0)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.QUICK_SECONDARY, 0.0)
            h.scheduler.tick(8)
            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            (h.visualPlays.single().from.z < 6.0) shouldBe true
        }
    }

    "new projectile bodies have one head and only trailing wake geometry" {
        listOf(StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA).forEach { spell ->
            val body = staffArsenalParts(spell, 6, 40, 12.0, 1.0, false, false, StaffCastInput.PRIMARY)
            (body.count { it.center.z >= 0f } <= 2) shouldBe true
            (body.count { it.center.z < -0.2f } >= 6) shouldBe true
            (body.size <= 16) shouldBe true
        }
    }

    "prism quick projectile respects configured maximum range" {
        withStaffHarness(settings = staffSettings(range = 8.0)) { h ->
            val world = h.paper.addSimpleWorld("arsenal-range")
            val player = h.player("arsenal-range", world, StaffSpell.PRISM)
            h.zombie(world, x = 0.5, z = 10.0)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.QUICK, 0.0)
            h.scheduler.tick(24)

            h.hits shouldBe emptyList()
        }
    }

    "prism contact attack cannot reach a target behind a wall" {
        withStaffHarness(settings = staffSettings(range = 12.0)) { h ->
            val world = h.paper.addSimpleWorld("arsenal-wall")
            val player = h.player("arsenal-wall", world, StaffSpell.PRISM)
            val target = h.zombie(world, x = 0.5, z = 7.0)
            for (x in -2..2) for (y in 63..67) world.getBlockAt(x, y, 3).type = Material.STONE
            h.controller.start()

            h.controller.cast(player, StaffCastInput.QUICK, 0.0)
            h.scheduler.tick(8)

            h.hits shouldBe emptyList()
            target.isValid shouldBe true
            h.projectileMoves.isEmpty() shouldBe true
            h.visualPlays.isNotEmpty() shouldBe true
        }
    }

    "piercing prism damages each encountered target once while passing through the line" {
        withStaffHarness(settings = staffSettings(range = 16.0, maxAreaTargets = 8)) { h ->
            val world = h.paper.addSimpleWorld("arsenal-pierce")
            val player = h.player("arsenal-pierce", world, StaffSpell.PRISM)
            val near = h.zombie(world, x = 0.5, z = 4.0)
            val far = h.zombie(world, x = 0.5, z = 8.0)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(18)

            h.hits.map { it.uniqueId }.toSet() shouldBe setOf(near.uniqueId, far.uniqueId)
            h.hits.map { it.uniqueId }.distinct().size shouldBe h.hits.size
        }
    }

    "quitting cancels arsenal motion and removes its effects" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-cancel")
            val player = h.player("arsenal-cancel", world, StaffSpell.GALE)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(2)
            val moveCount = h.projectileMoves.size
            h.controller.onQuit(PlayerQuitEvent(player, Component.empty()))
            val removedCount = h.removedEffects.size
            h.scheduler.tick(8)

            h.projectileMoves.size shouldBe moveCount
            h.removedEffects.size shouldBe removedCount
        }
    }

    "a stale duel cast stops during impact and reset removes active arsenal motion" {
        var current = true
        withStaffHarness(currentCast = { _, _ -> current }, hit = { _, _ -> current = false; true }) { h ->
            val world = h.paper.addSimpleWorld("arsenal-stale-duel")
            val player = h.player("arsenal-stale-duel", world, StaffSpell.PRISM)
            val target = h.zombie(world, x = 0.5, z = 4.0)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.QUICK, 0.0)
            h.scheduler.tick(8)

            h.hits.map { it.uniqueId } shouldBe listOf(target.uniqueId)
            h.removedEffects.isNotEmpty() shouldBe true
            val stoppedMoves = h.projectileMoves.size
            h.scheduler.tick(8)
            h.hits.size shouldBe 1
            h.projectileMoves.size shouldBe stoppedMoves
        }

        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("arsenal-reset")
            val player = h.player("arsenal-reset", world, StaffSpell.GALE)
            h.controller.start()

            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(2)
            val movesBeforeReset = h.projectileMoves.size
            movesBeforeReset shouldBe 1
            h.controller.resetPlayer(player.uniqueId)
            val removedAfterReset = h.removedEffects.size
            h.scheduler.tick(8)

            h.projectileMoves.size shouldBe movesBeforeReset
            h.removedEffects.size shouldBe removedAfterReset
        }
    }
})
