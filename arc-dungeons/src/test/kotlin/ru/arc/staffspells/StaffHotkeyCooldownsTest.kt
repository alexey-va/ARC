package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StaffHotkeyCooldownsTest : FreeSpec({
    "each player and ability owns one deadline shared by every staff" {
        var tick = 100L
        val cooldowns = StaffHotkeyCooldowns { tick }
        val owner = UUID.randomUUID()
        cooldowns.start(owner, StaffAbility.Q, 160)
        cooldowns.remaining(owner, StaffAbility.Q) shouldBe 160
        cooldowns.remaining(owner, StaffAbility.F) shouldBe 0
        cooldowns.remaining(UUID.randomUUID(), StaffAbility.Q) shouldBe 0
        cooldowns.start(owner, StaffAbility.PRIMARY, 20)
        cooldowns.remaining(owner, StaffAbility.SECONDARY) shouldBe 0
        tick += 20
        cooldowns.prune()
        cooldowns.remaining(owner, StaffAbility.PRIMARY) shouldBe 0
        cooldowns.remaining(owner, StaffAbility.Q) shouldBe 140
        cooldowns.total(owner, StaffAbility.Q) shouldBe 160
        cooldowns.clear(owner)
        cooldowns.remaining(owner, StaffAbility.Q) shouldBe 0
    }

    "a signed Bukkit tick rollover does not extend ability cooldowns" {
        var tick = Int.MAX_VALUE.toLong() - 2
        val cooldowns = StaffHotkeyCooldowns { tick }
        val owner = UUID.randomUUID()
        cooldowns.start(owner, StaffAbility.PRIMARY, 20)
        tick = Int.MIN_VALUE.toLong() + 2
        cooldowns.remaining(owner, StaffAbility.PRIMARY) shouldBe 15
    }

    "every available staff has distinct named Q and mobility F instructions with bounded cooldowns" {
        staffHotkeyDescriptions.keys shouldBe StaffSpell.available.toSet()
        staffHotkeyDescriptions.values.map { it.qName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.map { it.fName }.distinct().size shouldBe 16
        staffHotkeyDescriptions.values.all { it.qCooldown in 40..600 && it.fCooldown in 80..120 } shouldBe true
        StaffSpellConfig(StaffTestConfig()).hotkeyCooldown(StaffSpell.GALE, StaffHotkey.Q) shouldBe 600
    }
})
