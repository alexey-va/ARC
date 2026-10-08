package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
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

    "lore config changes replace ARC rows and remove only the two retired EM instructions" {
        val nativeLore = listOf(
            Component.text("Проклятие несъёмности I"),
            legacy.deserialize("&2Used to enchant items at the enchanter!"),
            Component.text("Staves only. Increases explosion radius."),
            legacy.deserialize("&2Used at the enchanter."),
        )
        val firstText = EliteEnchantmentBookPresentationText()
        val first = eliteEnchantmentBookLore(nativeLore, firstText)
        val changedText = firstText.copy(
            scopeLore = "Только для вещей EliteMobs",
            actionLore = "Перетащите книгу на снаряжение",
            previewHint = "Цена и шанс будут показаны заранее",
        )
        val changed = eliteEnchantmentBookLore(
            first.lore,
            changedText,
            previouslyOwnedRows = first.ownedRows,
            previouslyOwnedLeadingBlank = first.ownsLeadingBlank,
        )
        val rerendered = eliteEnchantmentBookLore(
            changed.lore,
            changedText,
            previouslyOwnedRows = changed.ownedRows,
            previouslyOwnedLeadingBlank = changed.ownsLeadingBlank,
        )

        changed.lore.map(plain::serialize) shouldBe listOf(
            "",
            "Только для вещей EliteMobs",
            "Проклятие несъёмности I",
            "Staves only. Increases explosion radius.",
            "Перетащите книгу на снаряжение",
            "Цена и шанс будут показаны заранее",
        )
        rerendered shouldBe changed
        changed.ownedRows shouldBe listOf(
            "Только для вещей EliteMobs",
            "Перетащите книгу на снаряжение",
            "Цена и шанс будут показаны заранее",
        )
    }

    "scope is placed under the native leading blank and source effect lore is preserved" {
        val nativeLore = listOf(
            Component.empty(),
            Component.text("Blast Radius I"),
            Component.text("Staves only. Increases explosion radius."),
        )
        val presentation = eliteEnchantmentBookLore(nativeLore, EliteEnchantmentBookPresentationText())

        presentation.lore.map(plain::serialize).take(3) shouldBe listOf(
            "",
            "Только для снаряжения EliteMobs",
            "Blast Radius I",
        )
        presentation.ownsLeadingBlank shouldBe false
        presentation.lore.last().let { plain.serialize(it) } shouldBe "Итог, цена и шансы — перед применением"
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
