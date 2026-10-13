package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class StaffSpellCooldownTest : FreeSpec({
    "bossbar progress fills monotonically and stays in the native range" {
        staffCooldownProgress(30, 30) shouldBe 0f
        staffCooldownProgress(15, 30) shouldBe 0.5f
        staffCooldownProgress(3, 30) shouldBe 0.9f
        staffCooldownProgress(0, 30) shouldBe 1f
        staffCooldownProgress(-1, 30) shouldBe 1f
        staffCooldownProgress(40, 30) shouldBe 0f
        staffCooldownProgress(10, 0) shouldBe 0f
    }

    "cooldown and ready bars identify the selected primary or shift input" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "chain.name" to "Цепь",
            "chain.secondary-name" to "Цепь веером",
            "messages.cooldown-bar" to "<ability> <seconds>s <input>",
            "messages.ready-bar" to "<ability> готово <input>",
        )))
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(config.cooldown(StaffSpell.CHAIN, secondary = true, remaining = 10, total = 20)) shouldBe
            "Цепь веером 0.5s Shift + ЛКМ"
        plain.serialize(config.cooldown(StaffSpell.CHAIN, secondary = false, remaining = 0, total = 20)) shouldBe
            "Цепь готово ЛКМ"
    }

    "ward cooldown names the Shift+LMB ability" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "frost.quick-name" to "Ледяные иглы",
            "frost.quick-secondary-name" to "Ледяной покров",
            "messages.shield-cooldown" to "<ability> <seconds> с · Shift + ЛКМ",
        )))
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(config.defense(StaffSpell.FROST, active = false, seconds = 25.0, allAround = true)) shouldBe
            "Ледяной покров 25.0 с · Shift + ЛКМ"
    }
})
