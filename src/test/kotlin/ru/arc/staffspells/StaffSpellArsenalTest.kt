package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent

class StaffSpellArsenalTest : FreeSpec({
    "each arsenal staff keeps four bounded, visibly distinct input silhouettes" {
        val inputs = StaffCastInput.entries
        listOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM,
            StaffSpell.PRISM, StaffSpell.NOVA).forEach { spell ->
            val states = inputs.map { input ->
                val parts = staffArsenalParts(spell, 5, 20, 12.0, 3.0, false,
                    input.secondary, input)
                parts.size shouldBe 32
                parts.all { part ->
                    part.center.x.isFinite() && part.center.y.isFinite() && part.center.z.isFinite() &&
                        part.scale.x.isFinite() && part.scale.y.isFinite() && part.scale.z.isFinite()
                } shouldBe true
                parts.filter { it.visible }.map { part ->
                    listOf(part.material, part.center.x, part.center.y, part.center.z,
                        part.scale.x, part.scale.y, part.scale.z)
                }
            }
            states.distinct().size shouldBe inputs.size
        }
        staffArsenalFront(5, 10, 4.0) shouldBe 2.0
        staffArsenalFront(20, 10, 4.0) shouldBe 4.0
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

    "prism quick projectile stops at a wall before reaching a target" {
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
            h.projectileMoves.isNotEmpty() shouldBe true
            (h.projectileMoves.last().at.z <= 3.05) shouldBe true
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
            movesBeforeReset shouldBe 2
            h.controller.resetPlayer(player.uniqueId)
            val removedAfterReset = h.removedEffects.size
            h.scheduler.tick(8)

            h.projectileMoves.size shouldBe movesBeforeReset
            h.removedEffects.size shouldBe removedAfterReset
        }
    }
})
