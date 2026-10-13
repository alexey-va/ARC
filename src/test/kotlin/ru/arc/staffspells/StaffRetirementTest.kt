package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify

class StaffRetirementTest : FreeSpec({
    "retired inferno stays identifiable but cannot cast channel or defense" {
        withStaffHarness { h ->
            val player = h.paper.addPlayer("retired-inferno")
            val item = h.config.item(StaffSpell.INFERNO)
            player.inventory.setItemInMainHand(item)
            StaffSpell.from(item) shouldBe StaffSpell.INFERNO
            StaffSpell.available.size shouldBe 16
            StaffSpell.available.contains(StaffSpell.INFERNO) shouldBe false
            h.controller.give(player, listOf(StaffSpell.INFERNO)) shouldBe false
            h.controller.canCharge(player, StaffSpell.INFERNO) shouldBe false
            h.controller.beginChannel(player, StaffSpell.INFERNO) shouldBe false
            h.controller.defend(player, StaffSpell.INFERNO)
            h.controller.cast(player, StaffCastInput.PRIMARY, 0.0)
            h.scheduler.tick(4)
            h.hits.size shouldBe 0
            h.visualPlays.size shouldBe 0
            verify(exactly = 0) { h.effects.defend(any(), any(), any(), any()) }
        }
    }
})
