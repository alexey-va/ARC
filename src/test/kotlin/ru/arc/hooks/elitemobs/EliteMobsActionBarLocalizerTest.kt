package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class EliteMobsActionBarLocalizerTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "fallback HUD localizes health and the active class resource without losing colours" {
        val source = Component.text("HP 84/120", NamedTextColor.RED)
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text("Fury 37/100", NamedTextColor.YELLOW))

        val localized = EliteMobsActionBarLocalizer().localize(source)

        plain.serialize(localized) shouldBe "Здоровье 84/120 | Ярость 37/100"
        localized.color() shouldBe NamedTextColor.RED
        localized.children().last().color() shouldBe NamedTextColor.YELLOW
    }

    "ability selector localizes mouse keys and catalog ability names" {
        val source = Component.text("[1/F] ", NamedTextColor.GRAY)
            .append(Component.text("Blink", NamedTextColor.WHITE))
            .append(Component.text("  [2/LMB] ", NamedTextColor.GRAY))
            .append(Component.text("Arcane Burst", NamedTextColor.WHITE))
            .append(Component.text("  [3/RMB] ", NamedTextColor.GRAY))
            .append(Component.text("Mana Ward", NamedTextColor.WHITE))
        val localizer = EliteMobsActionBarLocalizer(mapOf(
            "Blink" to "Скачок",
            "Arcane Burst" to "Чародейский взрыв",
            "Mana Ward" to "Магический заслон",
        ))

        plain.serialize(localizer.localize(source)) shouldBe
            "[1/F] Скачок  [2/ЛКМ] Чародейский взрыв  [3/ПКМ] Магический заслон"
    }

    "gradient ability receipt is rebuilt so its whole name and summary are Russian" {
        val gradientName = "Arcane Burst!".fold(Component.empty()) { result, character ->
            result.append(Component.text(character, NamedTextColor.GOLD))
        }
        val source = gradientName
            .append(Component.text(" 1.2x damage for 4s ", NamedTextColor.WHITE))
            .append(Component.text("-20 ", NamedTextColor.RED))
            .append(Component.text("Mana", NamedTextColor.WHITE))

        val localized = EliteMobsActionBarLocalizer(
            mapOf("Arcane Burst" to "Чародейский взрыв"),
        ).localize(source)

        plain.serialize(localized) shouldBe "Чародейский взрыв! 1.2× урона на 4 с -20 Мана"
    }

    "resource-pack HUD remains untouched because its glyph offsets were already measured" {
        val font = Key.key("elitemobs", "combat_hud_concept_16")
        val source = Component.text("\uE000").font(font)
            .append(Component.text("Mage"))
            .append(Component.text(" Fury 10/100"))

        val localized = EliteMobsActionBarLocalizer(mapOf("Mage" to "Маг")).localize(source)

        localized shouldBe source
        localized.font() shouldBe font
    }

    "known failures are translated but unrelated action bars remain untouched" {
        val localizer = EliteMobsActionBarLocalizer(mapOf("Arcane Burst" to "Чародейский взрыв"))
        plain.serialize(localizer.localize(Component.text("Not enough Mana (10/25 required)."))) shouldBe
            "Недостаточно ресурса: Мана (10/25 нужно)."

        val unrelated = Component.text("Mana market opens soon")
        localizer.localize(unrelated) shouldBe unrelated
    }

    "off-class warning translates its class, weapons and effect" {
        val localizer = EliteMobsActionBarLocalizer(mapOf(
            "Paladin" to "Паладин",
            "Swords" to "Мечи",
            "Axes" to "Топоры",
        ))
        val source = "Off-class weapon » -20% damage. Swords and Axes deal +10% more damage with Paladin."

        plain.serialize(localizer.localize(Component.text(source))) shouldBe
            "Неподходящее оружие » -20% урона. Мечи и Топоры наносят +10% больше урона с классом Паладин."
    }
})
