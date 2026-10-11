package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.TestConfig

class StaffDuelBalanceTest : FreeSpec({
    "arena tuning is reloadable, fingerprinted and leaves PvE damage intact" {
        val initial = StaffSpellConfig(TestConfig()).settings
        val changed = StaffSpellConfig(TestConfig(mapOf(
            "duel.chain.damage" to 5.0,
            "duel.chain.cooldown-ticks" to 30,
        ))).settings
        initial.tuningFor(StaffSpell.CHAIN, false).vanillaDamage shouldBe 8.0
        changed.tuningFor(StaffSpell.CHAIN, false) shouldBe initial.tuningFor(StaffSpell.CHAIN, false)
        changed.tuningFor(StaffSpell.CHAIN, true).vanillaDamage shouldBe 5.0
        changed.tuningFor(StaffSpell.CHAIN, true).cooldownTicks shouldBe 30L
        (changed.fingerprint("test") != initial.fingerprint("test")) shouldBe true
    }

    "arena primary ceiling permits burst without a one-shot or a fast-spam outlier" {
        val settings = StaffSpellConfig(TestConfig()).settings
        for (spell in staffDuelDefaults.keys - StaffSpell.NECROMANCER) {
            val tuning = settings.tuningFor(spell, true)
            val hit = tuning.vanillaDamage * .4
            val tapDps = hit / (tuning.cooldownTicks / 20.0)
            val fullDps = hit * 1.75 / (1.8 + tuning.cooldownTicks / 20.0)
            (tapDps in 1.5..2.25) shouldBe true
            (fullDps <= 1.9) shouldBe true
            (hit * 1.75 <= 7.0) shouldBe true
        }
    }
})
