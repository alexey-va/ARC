package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StaffHotkeyCooldownsTest : FreeSpec({
    "each spell and ability owns its deadline across staff swaps" {
        var tick = 100L
        val cooldowns = StaffHotkeyCooldowns { tick }
        val owner = UUID.randomUUID()
        cooldowns.start(owner, StaffSpell.CHAIN, StaffAbility.Q, 160)
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.Q) shouldBe 160
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.F) shouldBe 0
        cooldowns.remaining(owner, StaffSpell.FROST, StaffAbility.Q) shouldBe 0
        cooldowns.start(owner, StaffSpell.CHAIN, StaffAbility.PRIMARY, 20)
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.SECONDARY) shouldBe 0
        tick += 20
        cooldowns.prune()
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.PRIMARY) shouldBe 0
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.Q) shouldBe 140
        cooldowns.total(owner, StaffSpell.CHAIN, StaffAbility.Q) shouldBe 160
        cooldowns.clear(owner)
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.Q) shouldBe 0
    }

    "a signed Bukkit tick rollover does not extend ability cooldowns" {
        var tick = Int.MAX_VALUE.toLong() - 2
        val cooldowns = StaffHotkeyCooldowns { tick }
        val owner = UUID.randomUUID()
        cooldowns.start(owner, StaffSpell.CHAIN, StaffAbility.PRIMARY, 20)
        tick = Int.MIN_VALUE.toLong() + 2
        cooldowns.remaining(owner, StaffSpell.CHAIN, StaffAbility.PRIMARY) shouldBe 15
    }

    "every available staff has distinct named Q and mobility F instructions with bounded cooldowns" {
        staffHotkeyDescriptions.keys shouldBe StaffSpell.available.toSet()
        staffHotkeyDescriptions.values.map { it.qName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.map { it.fName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.all { it.qCooldown in 40..240 && it.fCooldown in 80..120 } shouldBe true
    }
})
