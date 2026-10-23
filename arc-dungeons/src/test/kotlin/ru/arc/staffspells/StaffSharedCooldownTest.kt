package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.util.UUID

class StaffSharedCooldownTest : FreeSpec({
    "Q recovery follows the player across all staves and permits only an existing continuation" {
        withStaffHarness { h ->
            every { h.effects.custom(any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
            every { h.effects.isActive(any()) } returns true
            val world = h.paper.addSimpleWorld("shared-q-budget")
            val player = h.player("shared-q-budget", world, StaffSpell.CHAIN)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.CHAIN, StaffHotkey.Q)
            h.captures.size shouldBe 1
            h.scheduler.tick(4)

            // Q can finish this orb; swapping cannot start another staff's Q.
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.Q) shouldBe true
            for (spell in StaffSpell.available.filter { it != StaffSpell.CHAIN }) {
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.canHotkey(player, spell, StaffHotkey.Q) shouldBe false
                h.controller.hotkey(player, spell, StaffHotkey.Q)
            }
            h.captures.size shouldBe 1
            h.controller.canHotkey(player, StaffSpell.GRAVITY, StaffHotkey.F) shouldBe true
            h.controller.canCharge(player, StaffSpell.GRAVITY) shouldBe true

            // Every staff unlocks at the original Q deadline.
            h.scheduler.tick(155)
            h.controller.canHotkey(player, StaffSpell.ICICLE, StaffHotkey.Q) shouldBe false
            h.scheduler.tick(1)
            StaffSpell.available.forEach { spell ->
                h.controller.canHotkey(player, spell, StaffHotkey.Q) shouldBe true
            }
        }
    }

    "F recovery is shared independently from Q and both attacks" {
        withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("shared-f-budget")
            val player = h.player("shared-f-budget", world, StaffSpell.GRAVITY)
            h.controller.start()
            h.controller.hotkey(player, StaffSpell.GRAVITY, StaffHotkey.F)
            h.captures.size shouldBe 1
            h.scheduler.tick(4)

            for (spell in StaffSpell.available) {
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.canHotkey(player, spell, StaffHotkey.F) shouldBe false
                h.controller.hotkey(player, spell, StaffHotkey.F)
            }
            h.captures.size shouldBe 1
            h.controller.canHotkey(player, StaffSpell.OSSUARY, StaffHotkey.Q) shouldBe true
            h.controller.canCharge(player, StaffSpell.OSSUARY) shouldBe true
            h.controller.canCharge(player, StaffSpell.OSSUARY, secondary = true) shouldBe true
            h.scheduler.tick(105)
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.F) shouldBe false
            h.scheduler.tick(1)
            h.controller.canHotkey(player, StaffSpell.CHAIN, StaffHotkey.F) shouldBe true
        }
    }

    "primary and secondary recovery each prevent swapping while keeping other inputs independent" {
        for (secondary in listOf(false, true)) withStaffHarness { h ->
            val world = h.paper.addSimpleWorld("shared-attack-$secondary")
            val player = h.player("shared-attack-$secondary", world, StaffSpell.CHAIN)
            h.controller.start()
            h.controller.cast(player, StaffCastInput.of(secondary, quick = false), 0.0)
            val total = if (secondary) 80L else 20L
            h.controller.skillRemaining(player.uniqueId, secondary) shouldBe total
            h.scheduler.tick(4)

            for (spell in StaffSpell.available) {
                player.inventory.setItemInMainHand(h.config.item(spell))
                h.controller.canCharge(player, spell, secondary) shouldBe false
                h.controller.cast(player, StaffCastInput.of(secondary, quick = false), 0.0)
                h.controller.canCharge(player, spell, !secondary) shouldBe true
            }
            h.captures.size shouldBe 1
            h.controller.canHotkey(player, StaffSpell.OSSUARY, StaffHotkey.Q) shouldBe true
            h.scheduler.tick(total - 5)
            h.controller.canCharge(player, StaffSpell.FROST, secondary) shouldBe false
            h.scheduler.tick(1)
            h.controller.canCharge(player, StaffSpell.FROST, secondary) shouldBe true
        }
    }
})
