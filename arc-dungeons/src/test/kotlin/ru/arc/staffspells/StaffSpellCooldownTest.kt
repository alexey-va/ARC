package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor

class StaffSpellCooldownTest : FreeSpec({
    "army HUD preserves fallback colors, bounded counts and configured templates" {
        val raw = StaffTestConfig()
        val config = StaffSpellConfig(raw)
        val plain = PlainTextComponentSerializer.plainText()
        val fallback = config.staffHudArmy(5, 3)
        plain.serialize(fallback) shouldBe "Войско 3/3"
        coloredContent(fallback)["Войско "] shouldBe 0x555555
        coloredContent(fallback).filterKeys { it != "Войско " }.values.toSet() shouldBe setOf(0xBDD8BB)
        plain.serialize(config.staffHudArmy(-1, 3)) shouldBe "Войско 0/3"

        raw["messages.staff-hud.army"] = "<gold><limit> · <count>"
        val configured = config.staffHudArmy(2, 3)
        plain.serialize(configured) shouldBe "3 · 2"
        coloredContent(configured).values.toSet() shouldBe setOf(0xFFAA00)
        raw["messages.staff-hud.army"] = ""
        config.staffHudArmy(2, 3) shouldBe Component.empty()
    }

    "HUD inputs and unstyled secondary names retain their accent inside white templates" {
        val config = StaffSpellConfig(StaffTestConfig(mapOf(
            "gravity.name" to "<#cfb8ff>Тяготение",
            "gravity.secondary-name" to "Обвал",
            "gravity.quick-name" to "Отпор",
            "gravity.quick-secondary-name" to "Покров",
            "messages.staff-hud.ready-instant" to "<white><input> · <ability>",
            "messages.staff-hud.charging" to "<white><input> · <ability> <percent>%",
            "messages.staff-hud.cooldown" to "<white><input> · <ability> <seconds> с",
            "messages.staff-hud.channel" to "<white><input> · <ability>",
            "messages.staff-hud.bossbar-ready-instant" to "<white><input> · <ability>",
            "messages.cooldown-bar" to "<white><input> · <ability> <seconds> с",
            "messages.defense-active" to "<white><input> · <ability>",
            "messages.shield-active" to "<white><ability> <remaining>",
        )))
        listOf(
            config.staffHudReady(StaffSpell.GRAVITY, true),
            config.staffHudCharging(StaffSpell.GRAVITY, true, .5, ""),
            config.staffHudCooldown(StaffSpell.GRAVITY, true, 20, ""),
            config.staffHudChannel(StaffSpell.GRAVITY, true),
            config.staffHudBossbar(StaffSpellHudState(StaffSpell.GRAVITY, true, StaffSpellHudPhase.READY)),
            config.cooldown(StaffSpell.GRAVITY, true, 20, 40),
        ).forEach { component ->
            coloredContent(component)["Shift + ПКМ"] shouldBe 0x73D9FF
            coloredContent(component)["Обвал"] shouldBe 0xCFB8FF
        }
        coloredContent(config.defense(StaffSpell.GRAVITY, true, 1.0))["Отпор"] shouldBe 0xCFB8FF
        coloredContent(config.shield(StaffSpell.GRAVITY, 4.0, 10.0))["Покров"] shouldBe 0xCFB8FF
    }

    "mobility cooldown defaults remain between four and six seconds with explicit configuration respected" {
        val defaults = StaffSpellConfig(StaffTestConfig(emptyMap()))
        StaffSpell.available.forEach { spell ->
            (defaults.hotkeyCooldown(spell, StaffHotkey.F) in 80L..120L) shouldBe true
        }
        val tuned = StaffSpellConfig(StaffTestConfig(mapOf("gravity.hotkeys.f.cooldown-ticks" to 90)))
        tuned.hotkeyCooldown(StaffSpell.GRAVITY, StaffHotkey.F) shouldBe 90L
    }

    "cooldown progress fills monotonically and stays in the native range" {
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
            "Цепь веером 0.5s Shift + ПКМ"
        plain.serialize(config.cooldown(StaffSpell.CHAIN, secondary = false, remaining = 0, total = 20)) shouldBe
            "Цепь готово ПКМ"
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

private fun coloredContent(component: Component, inherited: TextColor? = null): Map<String, Int?> {
    val color = component.color() ?: inherited
    return buildMap {
        if (component is TextComponent && component.content().isNotEmpty()) put(component.content(), color?.value())
        component.children().forEach { putAll(coloredContent(it, color)) }
    }
}
