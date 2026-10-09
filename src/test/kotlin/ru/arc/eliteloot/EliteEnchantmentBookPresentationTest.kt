package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

class EliteEnchantmentBookPresentationTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val legacy = LegacyComponentSerializer.legacyAmpersand()

    "legacy AE book marker also belongs to AE normalization" {
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("advancedenchantments", "book"))) shouldBe true
        hasAdvancedEnchantmentsBookMarker(setOf(NamespacedKey("another", "book"))) shouldBe false
    }

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
            " Сила I",
            "Только для снаряжения EliteMobs",
            "",
            " Только для посохов. Увеличивает радиус взрыва.",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
        lore[1] shouldBe enchantment
        lore[4] shouldBe authoredRestriction.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
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
            "Сила I",
            "Только для вещей EliteMobs",
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
            "Сила I",
            "Только для снаряжения EliteMobs",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
    }

    "native admission uses Bukkit applicability for ordinary items and EliteMobs allowlist for magic" {
        nativeBookCompatible(
            enchantments = listOf("power"),
            isMagicWeapon = false,
            supportedMagicEnchantments = setOf("power", "unbreaking"),
        ) { it == "power" } shouldBe true
        nativeBookCompatible(
            enchantments = listOf("power"),
            isMagicWeapon = false,
            supportedMagicEnchantments = setOf("power", "unbreaking"),
        ) { it == "sword" } shouldBe false
        nativeBookCompatible(
            enchantments = listOf("power"),
            isMagicWeapon = true,
            supportedMagicEnchantments = setOf("power", "unbreaking"),
        ) { false } shouldBe true
        nativeBookCompatible(
            enchantments = listOf("power", "sharpness"),
            isMagicWeapon = true,
            supportedMagicEnchantments = setOf("power", "unbreaking"),
        ) { false } shouldBe false
        nativeBookCompatible(
            enchantments = emptyList<String>(),
            isMagicWeapon = false,
            supportedMagicEnchantments = emptySet(),
        ) { false } shouldBe true
    }

    "Power's API-derived bow target and EliteMobs magic exception read as one exact lore row" {
        val magicAllowed = nativeBookCompatible(
            enchantments = listOf("power"),
            isMagicWeapon = true,
            supportedMagicEnchantments = setOf("power", "unbreaking"),
        ) { false }
        val targetLabels = eliteBookTargetLabels(
            ordinaryMaterials = setOf(Material.BOW),
            customRules = emptyList(),
            magicTargets = if (magicAllowed) listOf(
                EliteBookTargetProfile("WAND", setOf("MAINHAND"), setOf("WAND_MISSILE")),
                EliteBookTargetProfile("STAFF", setOf("MAINHAND"), setOf("STAFF_FIREBALL", "STAFF_MELEE")),
            ) else emptyList(),
        )!!
        plain.serialize(joinEliteBookTargetLabels(targetLabels)) shouldBe "луков, жезлов и посохов"

        val lore = eliteEnchantmentBookLore(
            enchantments = listOf(Component.text("Сила I")),
            authoredLore = emptyList(),
            text = EliteEnchantmentBookPresentationText(),
            compatibilityLore = eliteBookCompatibilityLoreLines(
                "Подходит для:",
                "совместимого снаряжения EliteMobs",
                targetLabels,
            ),
        )
        lore.map(plain::serialize) shouldBe listOf(
            "",
            "Сила I",
            "Только для снаряжения EliteMobs",
            "",
            "Подходит для: луков, жезлов и посохов",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
    }

    "chance rows stay with compatibility, preserve provider colors, and separate authored story" {
        val successColor = TextColor.color(0x65D788)
        val destructionColor = TextColor.color(0xFF716C)
        val success = Component.text("Шанс успеха: 65%", successColor)
        val destruction = Component.text("Разрушение при неудаче: 10%", destructionColor)
        val story = Component.text("Сила растёт вместе с волей владельца.")

        val lore = eliteEnchantmentBookLore(
            enchantments = listOf(Component.text("Сила I")),
            authoredLore = listOf(story),
            text = EliteEnchantmentBookPresentationText(),
            compatibilityLore = listOf(Component.text("Подходит для: луков")),
            chanceLore = listOf(success, destruction),
        )

        lore.map(plain::serialize) shouldBe listOf(
            "",
            "Сила I",
            "Только для снаряжения EliteMobs",
            "",
            "Подходит для: луков",
            "Шанс успеха: 65%",
            "Разрушение при неудаче: 10%",
            "",
            "Сила растёт вместе с волей владельца.",
            "",
            "[▶] Перетащите на предмет — зачаровать",
        )
        lore[5].color() shouldBe successColor
        lore[6].color() shouldBe destructionColor
        lore.forEach { it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
        lore.zipWithNext().none { (left, right) -> plain.serialize(left).isBlank() && plain.serialize(right).isBlank() } shouldBe true
    }

    "long compatibility lists wrap across rows without adding another section gap" {
        val lines = eliteBookCompatibilityLoreLines(
            label = "Подходит для:",
            fallback = "совместимого снаряжения EliteMobs",
            targets = listOf("мечей", "луков", "арбалетов", "трезубцев")
                .map(Component::text),
        )

        lines.map(plain::serialize) shouldBe listOf(
            "Подходит для: мечей, луков и арбалетов",
            "  трезубцев",
        )
        lines.zipWithNext().none { (left, right) ->
            plain.serialize(left).isBlank() && plain.serialize(right).isBlank()
        } shouldBe true
    }

    "custom applicability intersects exact native profiles, item types, slots, and attack kinds" {
        val bowOnly = EliteBookApplicabilityRule(setOf("BOW"), emptySet(), emptySet())
        val feetOnly = EliteBookApplicabilityRule(emptySet(), setOf("FEET"), emptySet())
        val wandOnly = EliteBookApplicabilityRule(setOf("WAND"), setOf("MAINHAND"), setOf("WAND_MISSILE"))
        val staffOnly = EliteBookApplicabilityRule(setOf("STAFF"), setOf("MAINHAND"), setOf("STAFF_FIREBALL"))
        eliteBookCustomRuleAllows(bowOnly, vanillaBookTargetProfile(Material.BOW)) shouldBe true
        eliteBookCustomRuleAllows(bowOnly, vanillaBookTargetProfile(Material.DIAMOND_SWORD)) shouldBe false
        eliteBookCustomRuleAllows(feetOnly, vanillaBookTargetProfile(Material.DIAMOND_BOOTS)) shouldBe true
        eliteBookCustomRuleAllows(feetOnly, vanillaBookTargetProfile(Material.DIAMOND_HELMET)) shouldBe false
        eliteBookCustomRuleAllows(wandOnly, EliteBookTargetProfile("WAND", setOf("MAINHAND"), setOf("WAND_MISSILE"))) shouldBe true
        eliteBookCustomRuleAllows(staffOnly, EliteBookTargetProfile("STAFF", setOf("MAINHAND"), setOf("STAFF_MELEE"))) shouldBe false

        eliteBookTargetLabels(
            ordinaryMaterials = listOf(Material.BOW, Material.DIAMOND_SWORD),
            customRules = listOf(bowOnly),
            magicTargets = listOf(
                EliteBookTargetProfile("WAND", setOf("MAINHAND"), setOf("WAND_MISSILE")),
                EliteBookTargetProfile("STAFF", setOf("MAINHAND"), setOf("STAFF_FIREBALL", "STAFF_MELEE")),
            ),
        )!!.map(plain::serialize) shouldBe listOf("луков")
        eliteBookCompatibleTargetLabels(emptyList(), listOf("elitemobs:missing")) shouldBe null
        eliteBookCompatibleTargetLabels(emptyList(), emptyList()) shouldBe null
    }

    "an unavailable custom catalog disables its lore projection without breaking book presentation" {
        MockBukkitTestRuntime.open().use {
            try {
                bindEliteEnchantmentCatalog(object : ClassLoader(null) {}) shouldBe false
                eliteBookCompatibleTargetLabels(emptyList(), listOf("elitemobs:plasma_boots")) shouldBe null
            } finally {
                clearEliteEnchantmentCatalog()
            }
        }
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

    "current MagmaCore and legacy EliteMobs lore ownership survive rebuilding and copying" {
        MockBukkitTestRuntime.open().use {
            for (namespace in listOf("magmacore", "elitemobs")) {
                val source = ItemStack(Material.ENCHANTED_BOOK).itemMeta
                source.setLore(listOf("scope", "effect"))
                val generated = source.getLore()!!.drop(1)
                val container = source.persistentDataContainer.adapterContext.newPersistentDataContainer()
                val root = NamespacedKey(namespace, "enchantment_presentation")
                val position = NamespacedKey(namespace, "position")
                container.set(NamespacedKey(namespace, "lines"), PersistentDataType.LIST.strings(), generated)
                container.set(position, PersistentDataType.INTEGER, 0)
                source.persistentDataContainer.set(root, PersistentDataType.TAG_CONTAINER, container)
                reindexEliteEnchantmentLore(source)
                val destination = ItemStack(Material.ENCHANTED_BOOK).itemMeta
                copyElitePresentationMetadata(source, destination)
                eliteGeneratedEnchantmentLore(destination) shouldBe generated
                destination.persistentDataContainer.get(root, PersistentDataType.TAG_CONTAINER)
                    ?.get(position, PersistentDataType.INTEGER) shouldBe 1
            }
        }
    }

})
