package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.TestConfig

class StaffSpellCooldownTest : FreeSpec({
    "cooldown segments progress monotonically and show a full bar when ready" {
        staffCooldownSegments(30, 30) shouldBe 0
        staffCooldownSegments(15, 30) shouldBe 5
        staffCooldownSegments(1, 30) shouldBe 9
        staffCooldownSegments(0, 30) shouldBe 10
        staffCooldownSegments(-1, 30) shouldBe 10
    }

    "cooldown and ready bars identify the selected primary or shift input" {
        val config = StaffSpellConfig(TestConfig(mapOf(
            "chain.name" to "Цепь",
            "chain.secondary-name" to "Цепь веером",
            "messages.cooldown-bar" to "<ability> <filled><empty> <seconds>s <input>",
            "messages.ready-bar" to "<ability> готово <input>",
        )))
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(config.cooldown(StaffSpell.CHAIN, secondary = true, remaining = 10, total = 20)) shouldBe
            "Цепь веером ▰▰▰▰▰▱▱▱▱▱ 0.5s Shift + ПКМ"
        plain.serialize(config.cooldown(StaffSpell.CHAIN, secondary = false, remaining = 0, total = 20)) shouldBe
            "Цепь готово ПКМ"
    }
})
