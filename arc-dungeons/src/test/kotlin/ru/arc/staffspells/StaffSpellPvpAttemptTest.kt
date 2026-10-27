package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class StaffSpellPvpAttemptTest : FreeSpec({
    "passive selection is silent while an offensive cast reports only a geometrically aimed protected target" {
        var attempts = 0
        withStaffHarness(deniedTarget = { _, target, _ ->
            if (target is Player) { attempts++; true } else false
        }) { h ->
            val world = h.paper.addSimpleWorld("pvp-attempt")
            val owner = h.player("pvp-caster", world, StaffSpell.CHAIN)
            val target = h.player("pvp-protected", world, StaffSpell.CHAIN)
            target.teleport(Location(world, .5, 64.0, 5.5))
            h.controller.start()
            h.scheduler.tick(8)
            attempts shouldBe 0

            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            // Input admission and the launch ray may both report; the shared policy owns message throttling.
            (attempts > 0) shouldBe true
            h.hits shouldBe emptyList()

            h.controller.resetPlayer(owner.uniqueId)
            attempts = 0
            target.teleport(Location(world, 5.5, 64.0, .5))
            owner.teleport(Location(world, .5, 64.0, .5).setDirection(Vector(0.0, 0.0, 1.0)))
            h.controller.cast(owner, StaffCastInput.PRIMARY, 0.0)
            attempts shouldBe 0
        }
    }
})
