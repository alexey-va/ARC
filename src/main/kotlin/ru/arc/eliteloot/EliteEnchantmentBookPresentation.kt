package ru.arc.eliteloot

import com.magmaguy.elitemobs.config.enchantments.EnchantmentsConfig
import com.magmaguy.elitemobs.items.EliteItemLore
import com.magmaguy.elitemobs.items.ItemTagger
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Player-facing copy shared by EliteMobs book rewards and the native enchanter. */
internal data class EliteEnchantmentBookPresentationText(
    val namePrefix: String = "Книга EliteMobs",
    val scopeLore: String = "Только для снаряжения EliteMobs",
    val compatibilityLabel: String = "Подходит для:",
    val compatibilityFallback: String = "совместимого снаряжения EliteMobs",
    val actionLore: String = "Перетащите на предмет — зачаровать",
)

private const val advancedEnchantmentsNamespace = "advancedenchantments"
private val aeBookMarkerKeys = setOf("ae_book", "ae_book_level", "ae_book_failure", "ae_book_success")

/** AdvancedEnchantments books carry these provider-authored PDC keys. */
internal fun hasAdvancedEnchantmentsBookMarker(keys: Set<NamespacedKey>): Boolean =
    keys.any { it.namespace == advancedEnchantmentsNamespace && it.key in aeBookMarkerKeys }

internal fun isAdvancedEnchantmentsBook(item: ItemStack): Boolean =
    item.type == Material.ENCHANTED_BOOK && item.itemMeta?.let {
        hasAdvancedEnchantmentsBookMarker(it.persistentDataContainer.keys)
    } == true

/** The provider marker and EliteMobs' own book reader distinguish EM books from vanilla books. */
internal fun isEliteEnchantmentBook(item: ItemStack): Boolean {
    if (item.type != Material.ENCHANTED_BOOK) return false
    if (item.itemMeta == null) return false
    if (isAdvancedEnchantmentsBook(item)) return false
    if (!ItemTagger.isEliteItem(item)) return false
    return EliteEnchantmentItems.isEliteEnchantmentBook(item)
}

private val plainBookText = PlainTextComponentSerializer.plainText()
private val bookNameAccent = TextColor.color(0xC7A0E8)
private val bookActionAccent = TextColor.color(0xC7A0E8)
private val bookBodyColor = TextColor.color(0xE6FFF3)
private val bookLabelColor = TextColor.color(0xB8B8B8)
private val bookStructureColor = TextColor.color(0x8C8C8C)
private val bookEnchantmentColor = TextColor.color(0x68D8FF)
private val bookLegacy = LegacyComponentSerializer.legacySection()
private val bookAmpersand = LegacyComponentSerializer.legacyAmpersand()

internal data class EliteEnchantmentBookNamePresentation(
    val name: Component?,
    val managedPrefix: String?,
    val detached: Boolean,
)

/**
 * Keep the public title short. If another plugin or the player changes our title later, detach
 * ARC ownership and preserve that rename across subsequent renders.
 */
internal fun eliteEnchantmentBookName(
    currentName: Component?,
    configuredPrefix: String,
    previousManagedPrefix: String? = null,
    detached: Boolean = false,
): EliteEnchantmentBookNamePresentation {
    val prefix = configuredPrefix.trim().ifEmpty { EliteEnchantmentBookPresentationText().namePrefix }
    if (detached) return EliteEnchantmentBookNamePresentation(currentName, null, true)

    if (previousManagedPrefix != null) {
        val currentPlain = currentName?.let(plainBookText::serialize).orEmpty()
        if (currentPlain != previousManagedPrefix) {
            return EliteEnchantmentBookNamePresentation(currentName, null, true)
        }
    }

    return EliteEnchantmentBookNamePresentation(
        Component.text(prefix, bookNameAccent).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE),
        prefix,
        false,
    )
}

private val retiredEnchanterInstructions = setOf(
    "Used to enchant items at the enchanter!",
    "Used at the enchanter.",
    "Используется для зачарования предметов у чародея!",
    "Используется у зачарователя.",
)

/** Rebuild a book from canonical effects and authored prose, never the equipment lore template. */
internal fun eliteEnchantmentBookLore(
    enchantments: List<Component>,
    authoredLore: List<Component>,
    text: EliteEnchantmentBookPresentationText,
    compatibilityLore: List<Component> = emptyList(),
): List<Component> = buildList {
    fun section(lines: List<Component>) {
        val compact = compactEliteLore(lines)
        if (compact.isEmpty()) return
        add(Component.empty().decoration(TextDecoration.ITALIC, false))
        addAll(compact)
    }
    section(listOfNotNull(text.scopeLore.trim().takeIf(String::isNotEmpty)?.let {
        bookLoreLine(it, bookLabelColor)
    }))
    section(compatibilityLore)
    section(enchantments)
    section(authoredLore.filterNot { plainBookText.serialize(it).trim() in retiredEnchanterInstructions })
    text.actionLore.trim().takeIf(String::isNotEmpty)?.let { action ->
        // The input is dragging a book, so do not advertise a plain LMB click as the gesture.
        section(listOf(Component.text("[", bookStructureColor)
            .append(Component.text("▶", bookActionAccent))
            .append(Component.text("] ", bookStructureColor))
            .append(bookLoreLine(action.substringBefore(" — "), bookActionAccent))
            .let { footer ->
                if (" — " in action) footer.append(Component.text(" — ", bookLabelColor))
                    .append(bookLoreLine(action.substringAfter(" — "), bookBodyColor)) else footer
            }))
    }
}

private fun bookLoreLine(value: String, color: TextColor): Component =
    Component.text(value, color).decoration(TextDecoration.ITALIC, false)

internal fun joinEliteBookTargetLabels(targets: List<Component>): Component =
    targets.withIndex().fold(Component.empty()) { joined, (index, target) ->
        if (index == 0) joined.append(target.color(bookEnchantmentColor))
        else joined
            .append(Component.text(if (index == targets.lastIndex) " и " else ", ", bookLabelColor))
            .append(target.color(bookEnchantmentColor))
    }

internal fun eliteBookCompatibilityLoreLines(
    label: String,
    fallback: String,
    targets: List<Component>?,
): List<Component> {
    if (targets.isNullOrEmpty()) {
        return listOf(
            bookLoreLine(label.trimEnd(), bookLabelColor)
                .append(Component.space())
                .append(bookLoreLine(fallback, bookBodyColor)),
        )
    }
    // ponytail: cap each tooltip row at three target tokens; wrap further items on continuation rows.
    return targets.chunked(3).mapIndexed { index, group ->
        val prefix = if (index == 0) {
            bookLoreLine(label.trimEnd(), bookLabelColor).append(Component.space())
        } else {
            Component.text("  ", bookLabelColor).decoration(TextDecoration.ITALIC, false)
        }
        prefix.append(joinEliteBookTargetLabels(group)).decoration(TextDecoration.ITALIC, false)
    }
}

private fun bookSourceLine(value: String): Component =
    bookLegacy.deserialize(bookLegacy.serialize(bookAmpersand.deserialize(value)))
        .decoration(TextDecoration.ITALIC, false)

private val bookNamePrefixKey = NamespacedKey("arc", "elitemobs_enchantment_book_name_prefix")
private val bookNameDetachedKey = NamespacedKey("arc", "elitemobs_enchantment_book_name_detached")
private val bookLoreRowsKey = NamespacedKey("arc", "elitemobs_enchantment_book_lore_rows")
private val bookLoreLeadingBlankKey = NamespacedKey("arc", "elitemobs_enchantment_book_lore_blank")

/** Keep native effect/ownership data intact while replacing the complete book tooltip. */
internal fun presentEliteEnchantmentBook(
    item: ItemStack,
    text: EliteEnchantmentBookPresentationText = ru.arc.enchanting.EnchantingModule.bookText,
): ItemStack {
    if (!isEliteEnchantmentBook(item)) return item

    val meta = item.itemMeta ?: return item
    val pdc = meta.persistentDataContainer
    val name = eliteEnchantmentBookName(
        meta.displayName(),
        text.namePrefix,
        pdc.get(bookNamePrefixKey, PersistentDataType.STRING),
        pdc.get(bookNameDetachedKey, PersistentDataType.BYTE)?.toInt() == 1,
    )
    name.name?.let(meta::displayName)
    if (name.detached) {
        pdc.remove(bookNamePrefixKey)
        pdc.set(bookNameDetachedKey, PersistentDataType.BYTE, 1.toByte())
    } else {
        pdc.set(bookNamePrefixKey, PersistentDataType.STRING, name.managedPrefix.orEmpty())
        pdc.remove(bookNameDetachedKey)
    }

    // Native rendering owns custom enchantment names/glyphs. Run it on a clone because it also
    // recalculates price; only its generated-lore ownership record belongs on the real book.
    val rendered = item.clone()
    rendered.editMeta(::reindexEliteEnchantmentLore)
    EliteItemLore(rendered, false)
    val renderedMeta = rendered.itemMeta
    copyElitePresentationMetadata(renderedMeta, meta)
    val customEnchantments = eliteGeneratedEnchantmentLore(renderedMeta).map(::bookSourceLine)
    val nativeLevels = EliteEnchantmentItems.nativeLevels(item).entries.sortedBy { it.key.key.toString() }
    val nativeEnchantments = nativeLevels.map { (enchantment, level) ->
            val name = EnchantmentsConfig.getEnchantment(enchantment)?.name
            val label = name?.let(::bookSourceLine) ?: enchantment.description()
            Component.empty().color(if (enchantment.isCursed) TextColor.color(0xFF716C) else bookEnchantmentColor)
                .append(label)
                .append(Component.space())
                // Keep the pack's native enchantment-level glyph, explicitly white.
                .append(Component.translatable("enchantment.level.$level", level.toString()).color(TextColor.color(0xFFFFFF)))
                .decoration(TextDecoration.ITALIC, false)
        }
    val authoredLore = ItemTagger.getCustomLore(renderedMeta).map(::bookSourceLine).toMutableList()
    if (pdc.has(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING)) {
        authoredLore += bookLoreLine("Привязано к душе", bookNameAccent)
    }
    val customLevels = EliteEnchantmentItems.custom(item)
    val targetLabels = eliteBookCompatibleTargetLabels(
        nativeLevels.map { it.key },
        customEnchantmentIds = customLevels.keys,
    )
    val compatibilityLore = eliteBookCompatibilityLoreLines(
        text.compatibilityLabel,
        text.compatibilityFallback,
        targetLabels,
    )
    meta.lore(eliteEnchantmentBookLore(nativeEnchantments + customEnchantments, authoredLore, text, compatibilityLore))
    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES)
    // Pre-redesign books used appended rows. Canonical reconstruction makes those markers obsolete.
    pdc.remove(bookLoreRowsKey)
    pdc.remove(bookLoreLeadingBlankKey)

    // Native generated-lore ownership records store a row range; filtering or replacing rows may
    // shift that range, so update it after every book render.
    reindexEliteEnchantmentLore(meta)
    item.itemMeta = meta
    return item
}
