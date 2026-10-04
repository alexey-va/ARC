package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime

private val testElitePresentationRoot = NamespacedKey("elitemobs", "enchantment_presentation")
private val testElitePresentationLines = NamespacedKey("elitemobs", "lines")
private val testElitePresentationPosition = NamespacedKey("elitemobs", "position")

private fun setTestElitePresentation(meta: org.bukkit.inventory.meta.ItemMeta, lines: List<String>, position: Int) {
    val presentation = meta.persistentDataContainer.adapterContext.newPersistentDataContainer()
    presentation.set(testElitePresentationLines, PersistentDataType.LIST.strings(), lines)
    presentation.set(testElitePresentationPosition, PersistentDataType.INTEGER, position)
    meta.persistentDataContainer.set(testElitePresentationRoot, PersistentDataType.TAG_CONTAINER, presentation)
}

private fun elitePresentationRange(meta: org.bukkit.inventory.meta.ItemMeta): Pair<List<String>, Int> {
    val presentation = meta.persistentDataContainer.get(testElitePresentationRoot, PersistentDataType.TAG_CONTAINER)!!
    return presentation.get(testElitePresentationLines, PersistentDataType.LIST.strings())!! to
        presentation.get(testElitePresentationPosition, PersistentDataType.INTEGER)!!
}

class EliteLootPresentationTest : FreeSpec({
    "magic weapon lore replaces only its native physical DPS row and stays stable" {
        val plain = PlainTextComponentSerializer.plainText()
        val entry = "&f &cБазовый элитный DPS: &a\$EDPS"
        for (template in listOf(entry, entry.replace('&', '§'))) {
            val row = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(entry.replace("\$EDPS", "4.0"))
                .decoration(TextDecoration.ITALIC, true)
            val lore = listOf(Component.text("Уровень: 43"), row, Component.text("Описание заклинания"))
            val updated = replaceMagicEliteDpsLore(lore, true, template)
            updated.map(plain::serialize) shouldBe listOf(
                "Уровень: 43",
                " Магический урон по элитам",
                "Зависит от уровня, навыка и заклинания",
                "Описание заклинания",
            )
            updated[1].decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            updated[2].decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            replaceMagicEliteDpsLore(updated, true, template) shouldBe updated
        }
    }
    "physical items and unrelated or newer native stats retain their lore" {
        val entry = "&f &cБазовый элитный DPS: &a\$EDPS"
        val nativeRow = listOf(Component.text(" Базовый элитный DPS: 4.25"))
        replaceMagicEliteDpsLore(nativeRow, false, entry) shouldBe nativeRow
        replaceMagicEliteDpsLore(nativeRow, true, "Уровень оружия: \$itemLevel") shouldBe nativeRow
        replaceMagicEliteDpsLore(nativeRow, true, "\$EDPS + \$EDPS") shouldBe nativeRow
        val customLore = listOf(Component.text("Описание:  Базовый элитный DPS: 4.25"))
        replaceMagicEliteDpsLore(customLore, true, entry) shouldBe customLore
    }
    "tooltip progression has stable boundaries independent of resale price" {
        listOf(0, 19, 20, 39, 40, 59, 60, 79, 80, 99, 100, 190).map(::eliteTooltipTier) shouldBe
            listOf("common", "common", "uncommon", "uncommon", "rare", "rare", "epic", "epic", "legendary", "legendary", "artifact", "artifact")
    }
    "ground effects last until their item or display disappears" {
        eliteEffectFinished(true, true) shouldBe false
        eliteEffectFinished(true, false) shouldBe true
        eliteEffectFinished(false, true) shouldBe true
    }
    "effect floor clears the item origin after scaling without moving the item" {
        val origin = org.bukkit.Location(null, 1.0, 20.0, 3.0, 90f, 20f)
        val position = eliteEffectPosition(origin)
        position.y shouldBe 21.35
        (position.y - 1.25 > origin.y) shouldBe true
        position.yaw shouldBe 0f
        origin.y shouldBe 20.0
    }
    "drop effect colors follow the same rarity boundaries as tooltips" {
        listOf(0, 20, 40, 60, 80, 100).map { eliteLootColor(it).asRGB() } shouldBe
            listOf(0xE5F2FF, 0x72EE99, 0x55BBFF, 0xCF77FF, 0xFFC14D, 0xFF7066)
    }
    "lore removes all leading and trailing blanks but keeps section gaps" {
        val lines = listOf("", " ", "Уровень: 43", "", " ", "Описание", "", "")
        val result = compactEliteLore(lines.map(Component::text))
        result.map(PlainTextComponentSerializer.plainText()::serialize) shouldBe listOf("Уровень: 43", "", "Описание")
        compactEliteLore(result) shouldBe result
    }
    "generated lore reindexing accepts only a unique exact replacement range" {
        reindexedEliteEnchantmentLorePosition(
            listOf("Уровень: 43", "Сгенерированная строка", "Описание"),
            listOf("Сгенерированная строка"),
            0,
        ) shouldBe 1
        reindexedEliteEnchantmentLorePosition(
            listOf("Сгенерированная строка", "Сгенерированная строка"),
            listOf("Сгенерированная строка"),
            9,
        ) shouldBe null
        reindexedEliteEnchantmentLorePosition(
            listOf("Уровень: 43", "Описание"),
            listOf("Сгенерированная строка"),
            0,
        ) shouldBe null
    }
    "reindexes generated lore after compaction and remains valid on repeated presentation passes" {
        MockBukkitTestRuntime.open().use {
            val generated = listOf("Зачарование: Огненный удар", "Урон: 4")
            val compacted = compactEliteLore(
                (listOf("Уровень: 43", "", "") + generated + "Описание").map(Component::text),
            )
            val item = ItemStack(Material.DIAMOND_SWORD)
            item.editMeta { meta ->
                // The host recorded index 3 before ARC collapsed two adjacent blank rows to one.
                meta.lore(compacted)
                setTestElitePresentation(meta, generated, 3)
            }

            repeat(2) {
                item.editMeta(::reindexEliteEnchantmentLore)
                val meta = item.itemMeta
                val (storedLines, position) = elitePresentationRange(meta)
                val visibleLines = meta.lore().orEmpty().map(PlainTextComponentSerializer.plainText()::serialize)
                visibleLines.subList(position, position + storedLines.size) shouldBe storedLines
                position shouldBe 2

                // Exercise the real ARC compactor and verify the host range after each presentation pass.
                item.editMeta { it.lore(compactEliteLore(it.lore().orEmpty())) }
                item.editMeta(::reindexEliteEnchantmentLore)
                val finalMeta = item.itemMeta
                val (finalLines, finalPosition) = elitePresentationRange(finalMeta)
                val finalLore = finalMeta.lore().orEmpty().map(PlainTextComponentSerializer.plainText()::serialize)
                finalLore.subList(finalPosition, finalPosition + finalLines.size) shouldBe finalLines
                finalPosition shouldBe 2
            }
        }
    }
    "copying native presentation metadata leaves the original soulbind and price untouched" {
        MockBukkitTestRuntime.open().use {
            val ownerKey = NamespacedKey("elitemobs", "soulbind")
            val priceKey = NamespacedKey("elitemobs", "itemvalue")
            val source = ItemStack(Material.DIAMOND_SWORD)
            source.editMeta { meta ->
                setTestElitePresentation(meta, listOf("Generated enchantment"), 1)
                meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, "rerendered-owner")
                meta.persistentDataContainer.set(priceKey, PersistentDataType.DOUBLE, 999.0)
            }
            val original = ItemStack(Material.DIAMOND_SWORD)
            original.editMeta { meta ->
                setTestElitePresentation(meta, listOf("Stale enchantment"), 0)
                meta.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, "original-owner")
                meta.persistentDataContainer.set(priceKey, PersistentDataType.DOUBLE, 37.5)
            }

            val originalMeta = original.itemMeta
            copyElitePresentationMetadata(source.itemMeta, originalMeta)
            original.itemMeta = originalMeta

            val copiedLines = elitePresentationRange(original.itemMeta).first
            copiedLines shouldBe listOf("Generated enchantment")
            original.itemMeta.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) shouldBe "original-owner"
            original.itemMeta.persistentDataContainer.get(priceKey, PersistentDataType.DOUBLE) shouldBe 37.5
        }
    }
    "gear requirement is owned by ARC and sits directly below the item level" {
        val plain = PlainTextComponentSerializer.plainText()
        val lore = listOf(
            "Уровень: 16 · Престиж: 0",
            "\$ifSkillRequirement\$skillRequirement",
            "Базовая элитная защита: 4.25",
            " старая строка",
        )

        replaceEliteGearRequirement(lore.map(Component::text), "Броня", 16)
            .map(plain::serialize) shouldBe listOf(
                "Уровень: 16 · Престиж: 0",
                " Навык не требуется",
                "Базовая элитная защита: 4.25",
            )

        plain.serialize(eliteGearRequirementLine("Мечи", 43)) shouldBe " Требуется: Мечи · ур. 43"
    }
    "legacy procedural item text is localized without changing component styling" {
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(localizeLegacyEliteText(Component.text("Traveling Mallet"))) shouldBe "Походный молот"
        listOf(
            "Too heavy for a sensible pack.",
            "A very sensible thing to have in a fight.",
            "Light straps leave room for a full stride.",
            "The scouts always leave before breakfast.",
        ).map { plain.serialize(localizeLegacyEliteText(Component.text(it))) } shouldBe listOf(
            "Слишком тяжёл для обычного рюкзака.",
            "Зато в бою без него никуда.",
            "Лёгкие ремни не стесняют шага.",
            "Разведчики всегда выходят до завтрака.",
        )
    }
})
