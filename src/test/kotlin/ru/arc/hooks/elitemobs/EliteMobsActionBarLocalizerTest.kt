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

    "only the exact alpha promotional system notice is suppressed" {
        val localizer = EliteMobsActionBarLocalizer()
        val notice = Component.text("[Alpha] Advanced Combat System", NamedTextColor.GOLD)
            .append(Component.text(" is active here. The combat system is still in alpha, ", NamedTextColor.GRAY))
            .append(Component.text("but testers have found it extremely enjoyable. ", NamedTextColor.GRAY))
            .append(Component.text("Please share your feedback with ", NamedTextColor.WHITE))
            .append(Component.text("the developer!", NamedTextColor.YELLOW))

        localizer.shouldSuppressAlphaNotice(notice, isOverlay = false) shouldBe true
        localizer.shouldSuppressAlphaNotice(
            Component.text(
                "[Alpha] Advanced Combat System is active here. The combat system is still in alpha, " +
                    "but class data is still loading. Please share your feedback with the developer!",
            ),
            isOverlay = false,
        ) shouldBe false
        localizer.shouldSuppressAlphaNotice(
            Component.text("Warning: Please share your feedback with the developer after reporting this issue."),
            isOverlay = false,
        ) shouldBe false
        localizer.shouldSuppressAlphaNotice(notice, isOverlay = true) shouldBe false
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

    "native off-class chat translates the complete gradient notice with one sword prefix" {
        val localizer = EliteMobsActionBarLocalizer(mapOf("Colossus" to "Колосс", "Maces" to "Булавы"))
        val source = "Off-class weapon".fold(Component.empty()) { result, character ->
            result.append(Component.text(character, NamedTextColor.RED))
        }.append(Component.text(" » This weapon does not match your class: -20% damage. Maces deal 10% more damage with Colossus."))
        val translated = localizer.localizeChatNotice(source)
        plain.serialize(translated) shouldBe "⚔ Неподходящее оружие: −20% урона. Колосс · Булавы: +10% урона."
        translated.color() shouldBe NamedTextColor.WHITE
        localizer.localizeChatNotice(translated) shouldBe translated
        listOf(
            "Player: " + plain.serialize(source),
            "Off-class weapon » This weapon does not match your class: configuration error.",
            "Maces deal 10% more damage with Colossus.",
        ).forEach { message ->
            val other = Component.text(message)
            localizer.localizeChatNotice(other) shouldBe other
        }
    }

    "chat onboarding notices translate their gradient heading and complete instructions" {
        val localizer = EliteMobsActionBarLocalizer()
        val noClass = "No class active!".fold(Component.empty()) { result, character ->
            result.append(Component.text(character, NamedTextColor.GOLD))
        }.append(Component.text(" Open /em class and pick a free class to use abilities here."))
        val controls = Component.text("[Alpha] Advanced Combat System", NamedTextColor.GOLD)
            .append(Component.text(" » New: hold sneak and double-tap F ", NamedTextColor.GRAY))
            .append(Component.text("to toggle class controls anywhere outside EliteMobs content."))

        plain.serialize(localizer.localizeChatNotice(noClass)) shouldBe
            "Класс не выбран. Выберите бесплатный класс: Shift + F → Классы."
        plain.serialize(localizer.localizeChatNotice(controls)) shouldBe
            "Управление классом » Вне данжей его можно переключать: зажмите Shift и дважды нажмите F."
        localizer.shouldSuppressAlphaNotice(noClass, isOverlay = false) shouldBe false
        localizer.shouldSuppressAlphaNotice(controls, isOverlay = false) shouldBe false
    }

    "chat localization preserves combat errors and unrelated messages sharing the heading or class names" {
        val localizer = EliteMobsActionBarLocalizer(mapOf("Mage" to "Маг"))
        listOf(
            "[Alpha] Advanced Combat System is disabled on this server.",
            "Your [Alpha] Advanced Combat System class data could not be loaded. Please report this to the developer.",
            "No class active! Class data is still loading.",
            "Mage: No class active! Open /em class and pick a free class to use abilities here.",
            "[EliteMobs] A message from the developer about the new combat system.",
        ).forEach { text ->
            val notice = Component.text(text)
            localizer.localizeChatNotice(notice) shouldBe notice
        }
    }
})
