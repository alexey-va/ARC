package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey

class EliteEnchantmentBookPresentationTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val legacy = LegacyComponentSerializer.legacyAmpersand()

    "book name is short and config changes replace its previous prefix" {
        val nativeName = Component.text("Elite Soul Speed Enchanted Book")
            .decoration(TextDecoration.ITALIC, TextDecoration.State.TRUE)
        val first = eliteEnchantmentBookName(nativeName, "Книга EliteMobs")
        val changed = eliteEnchantmentBookName(
            first.name,
            "Книга зачарований EliteMobs",
            first.managedPrefix,
        )

        plain.serialize(first.name!!) shouldBe "Книга EliteMobs"
        plain.serialize(changed.name!!) shouldBe "Книга зачарований EliteMobs"
        changed.managedPrefix shouldBe "Книга зачарований EliteMobs"
        changed.name?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    }

    "a later external rename remains intact across config changes" {
        val first = eliteEnchantmentBookName(Component.text("Native title"), "Книга EliteMobs")
        val renamed = eliteEnchantmentBookName(
            Component.text("Название от игрока"),
            "Книга зачарований EliteMobs",
            first.managedPrefix,
        )
        val renderedAgain = eliteEnchantmentBookName(
            renamed.name,
            "Новое имя из конфига",
            previousManagedPrefix = renamed.managedPrefix,
            detached = renamed.detached,
        )

        plain.serialize(renamed.name!!) shouldBe "Название от игрока"
        renamed.detached shouldBe true
        plain.serialize(renderedAgain.name!!) shouldBe "Название от игрока"
        renderedAgain.detached shouldBe true
    }

    "book lore keeps canonical effects and applicability, removes exact obsolete instructions, and groups rows" {
        val enchantment = Component.text(" ", TextColor.color(0xFFFFFF))
            .append(Component.text("Сила I", TextColor.color(0x68D8FF)))
            .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        val authoredRestriction = Component.text(" ", TextColor.color(0xFFFFFF))
            .append(Component.text("Только для посохов. Увеличивает радиус взрыва.", TextColor.color(0xE6FFF3)))
            .decoration(TextDecoration.ITALIC, TextDecoration.State.TRUE)
        val authoredLore = listOf(
            legacy.deserialize("&2Used to enchant items at the enchanter!"),
            legacy.deserialize("&2Used at the enchanter."),
            legacy.deserialize("&2Используется у зачарователя."),
            legacy.deserialize("&2Используется для зачарования предметов у чародея!"),
            authoredRestriction,
        )

        val lore = eliteEnchantmentBookLore(
            enchantments = listOf(enchantment),
            authoredLore = authoredLore,
            text = EliteEnchantmentBookPresentationText(actionLore = "Перетащите на предмет — зачаровать"),
        )

        lore.map(plain::serialize) shouldBe listOf(
            "",
            "Только для снаряжения EliteMobs",
            "",
            " Сила I",
            "",
            " Только для посохов. Увеличивает радиус взрыва.",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
        lore[3] shouldBe enchantment
        lore[5] shouldBe authoredRestriction.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        lore.forEach { it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
        lore.zipWithNext().none { (left, right) -> plain.serialize(left).isBlank() && plain.serialize(right).isBlank() } shouldBe true
    }

    "fresh lore input replaces old text when configuration changes" {
        val enchantments = listOf(Component.text("Сила I").decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE))
        val authoredLore = listOf(Component.text("Только для лука").decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE))
        val first = eliteEnchantmentBookLore(
            enchantments,
            authoredLore,
            EliteEnchantmentBookPresentationText(
                scopeLore = "Только для снаряжения EliteMobs",
                actionLore = "Перетащите книгу",
            ),
        )
        val changed = eliteEnchantmentBookLore(
            enchantments,
            authoredLore,
            EliteEnchantmentBookPresentationText(
                scopeLore = "Только для вещей EliteMobs",
                actionLore = "Перетащите на предмет — зачаровать",
            ),
        )

        first.map(plain::serialize).contains("Только для снаряжения EliteMobs") shouldBe true
        first.map(plain::serialize).contains("[▶] Перетащите книгу") shouldBe true
        changed.map(plain::serialize) shouldBe listOf(
            "",
            "Только для вещей EliteMobs",
            "",
            "Сила I",
            "",
            "Только для лука",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
        changed.map(plain::serialize).none {
            it == "Только для снаряжения EliteMobs" || it == "[▶] Перетащите книгу"
        } shouldBe true
    }

    "authored lore group and its separator are omitted when only obsolete instructions remain" {
        val lore = eliteEnchantmentBookLore(
            enchantments = listOf(Component.text("Сила I")),
            authoredLore = listOf(
                legacy.deserialize("&2Used to enchant items at the enchanter!"),
                legacy.deserialize("&2Используется для зачарования предметов у чародея!"),
            ),
            text = EliteEnchantmentBookPresentationText(),
        )

        lore.map(plain::serialize) shouldBe listOf(
            "",
            "Только для снаряжения EliteMobs",
            "",
            "Сила I",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
    }

    "AE book PDC markers are recognized by their provider-authored key names" {
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("advancedenchantments", "ae_book"))) shouldBe true
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("advancedenchantments", "ae_book_level"))) shouldBe true
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("other", "ae_book"))) shouldBe false
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("other", "ae_custom_book"))) shouldBe false
    }

    "native generated lore range is reindexed after presentation rows shift it" {
        reindexedEliteEnchantmentLorePosition(
            hostLore = listOf("", "Только для снаряжения EliteMobs", "Blast Radius I", "Staves only"),
            generatedLore = listOf("Blast Radius I"),
            recordedPosition = 0,
        ) shouldBe 2
    }

})
