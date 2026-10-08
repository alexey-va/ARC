package ru.arc.eliteloot

import com.magmaguy.elitemobs.items.ItemTagger
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Player-facing copy shared by EliteMobs book rewards and the native enchanter. */
internal data class EliteEnchantmentBookPresentationText(
    val namePrefix: String = "Книга EliteMobs",
    val scopeLore: String = "Только для снаряжения EliteMobs",
    val actionLore: String = "Перетащите книгу на предмет в инвентаре",
    val previewHint: String = "Итог, цена и шансы — перед применением",
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
private val bookHintColor = TextColor.color(0xC8C0D1)

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

internal data class EliteEnchantmentBookLorePresentation(
    val lore: List<Component>,
    val ownedRows: List<String>,
    val ownsLeadingBlank: Boolean,
)

private val retiredEnchanterInstructions = setOf(
    "Used to enchant items at the enchanter!",
    "Used at the enchanter.",
)

private fun removePreviouslyOwnedRows(currentLore: List<Component>, ownedRows: List<String>): MutableList<Component> {
    val remaining = currentLore.toMutableList()
    ownedRows.forEachIndexed { ownedIndex, ownedText ->
        // Scope is ARC-owned at row 1 (after the leading blank); action and hint are appended.
        val preferredIndex = if (ownedIndex == 0) 1 else remaining.lastIndex
        val index = if (preferredIndex in remaining.indices &&
            plainBookText.serialize(remaining[preferredIndex]) == ownedText
        ) {
            preferredIndex
        } else if (ownedIndex == 0) {
            remaining.indexOfFirst { plainBookText.serialize(it) == ownedText }
        } else {
            remaining.indexOfLast { plainBookText.serialize(it) == ownedText }
        }
        if (index >= 0) remaining.removeAt(index)
    }
    return remaining
}

/** Re-render only ARC-owned rows so a live text-config change replaces the old copy. */
internal fun eliteEnchantmentBookLore(
    currentLore: List<Component>,
    text: EliteEnchantmentBookPresentationText,
    previouslyOwnedRows: List<String> = emptyList(),
    previouslyOwnedLeadingBlank: Boolean = false,
): EliteEnchantmentBookLorePresentation {
    val lore = removePreviouslyOwnedRows(currentLore, previouslyOwnedRows)
    if (previouslyOwnedLeadingBlank && lore.firstOrNull()?.let { plainBookText.serialize(it).isBlank() } == true) {
        lore.removeAt(0)
    }

    // These are the two exact EM source rows retired with the old guild workflow. Keep other
    // authored effect restrictions (for example, "Staves only") untouched.
    lore.removeAll { plainBookText.serialize(it) in retiredEnchanterInstructions }

    val ownedRows = mutableListOf<String>()
    var ownsLeadingBlank = false
    val scope = text.scopeLore.trim()
    if (scope.isNotEmpty() && lore.none { plainBookText.serialize(it) == scope }) {
        if (lore.firstOrNull()?.let { plainBookText.serialize(it).isBlank() } != true) {
            lore.add(0, Component.empty().decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE))
            ownsLeadingBlank = true
        }
        lore.add(if (lore.isEmpty()) 0 else 1, bookLoreLine(scope, bookBodyColor))
        ownedRows += scope
    }

    val tailLines = listOf(
        text.actionLore.trim() to bookActionAccent,
        text.previewHint.trim() to bookHintColor,
    ).filter { (value, _) -> value.isNotEmpty() }
    tailLines.distinctBy { (value, _) -> value }.forEach { (value, color) ->
        if (lore.none { plainBookText.serialize(it) == value } && value !in ownedRows) {
            lore += bookLoreLine(value, color)
            ownedRows += value
        }
    }

    return EliteEnchantmentBookLorePresentation(lore, ownedRows, ownsLeadingBlank)
}

private fun bookLoreLine(value: String, color: TextColor): Component =
    Component.text(value, color).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)

private val bookNamePrefixKey = NamespacedKey("arc", "elitemobs_enchantment_book_name_prefix")
private val bookNameDetachedKey = NamespacedKey("arc", "elitemobs_enchantment_book_name_detached")
private val bookLoreRowsKey = NamespacedKey("arc", "elitemobs_enchantment_book_lore_rows")
private val bookLoreLeadingBlankKey = NamespacedKey("arc", "elitemobs_enchantment_book_lore_blank")

/** Add the ARC ownership/action note without changing native effect lore or EM ownership data. */
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

    val presentation = eliteEnchantmentBookLore(
        meta.lore().orEmpty(),
        text,
        pdc.get(bookLoreRowsKey, PersistentDataType.LIST.strings()).orEmpty(),
        pdc.get(bookLoreLeadingBlankKey, PersistentDataType.BYTE)?.toInt() == 1,
    )
    meta.lore(presentation.lore)
    if (presentation.ownedRows.isEmpty()) {
        pdc.remove(bookLoreRowsKey)
    } else {
        pdc.set(bookLoreRowsKey, PersistentDataType.LIST.strings(), presentation.ownedRows)
    }
    if (presentation.ownsLeadingBlank) {
        pdc.set(bookLoreLeadingBlankKey, PersistentDataType.BYTE, 1.toByte())
    } else {
        pdc.remove(bookLoreLeadingBlankKey)
    }

    // Native generated-lore ownership records store a row range; filtering or replacing rows may
    // shift that range, so update it after every book render.
    reindexEliteEnchantmentLore(meta)
    item.itemMeta = meta
    return item
}
