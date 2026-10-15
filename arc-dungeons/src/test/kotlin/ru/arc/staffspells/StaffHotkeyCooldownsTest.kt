package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StaffHotkeyCooldownsTest : FreeSpec({
    "Q and F have independent budgets while rapid attack combinations share a short admission window" {
        var tick = 100L
        val cooldowns = StaffHotkeyCooldowns { tick }
        val owner = UUID.randomUUID()
        cooldowns.start(owner, StaffHotkey.Q, 160)
        cooldowns.remaining(owner, StaffHotkey.Q) shouldBe 160
        cooldowns.remaining(owner, StaffHotkey.F) shouldBe 0
        cooldowns.actionReady(owner) shouldBe false
        tick += 4
        cooldowns.actionReady(owner) shouldBe true
        cooldowns.start(owner, StaffHotkey.F, 120)
        cooldowns.remaining(owner, StaffHotkey.Q) shouldBe 156
        cooldowns.remaining(owner, StaffHotkey.F) shouldBe 120
        tick += 120
        cooldowns.remaining(owner, StaffHotkey.F) shouldBe 0
        cooldowns.remaining(owner, StaffHotkey.Q) shouldBe 36
        cooldowns.clear(owner)
        cooldowns.remaining(owner, StaffHotkey.Q) shouldBe 0
    }

    "every available staff has distinct named Q and mobility F instructions with bounded cooldowns" {
        staffHotkeyDescriptions.keys shouldBe StaffSpell.available.toSet()
        staffHotkeyDescriptions.values.map { it.qName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.map { it.fName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.all { it.qCooldown in 40..240 && it.fCooldown in 120..240 } shouldBe true
    }
})
