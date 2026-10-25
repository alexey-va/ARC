package ru.arc.listeners

import dev.lone.itemsadder.api.CustomStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.flattener.ComponentFlattener
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.util.Logging

/** Migrates literal attribute lore that ItemsAdder's translation-key updater cannot recognize. */
internal object LegacyItemsAdderAttributes {
    private const val MAIN_HAND = "item.modifiers.mainhand"
    private val plain = PlainTextComponentSerializer.builder()
        .flattener(ComponentFlattener.basic().toBuilder().mapper(TranslatableComponent::class.java) { it.key() }.build())
        .build()
    private val gap = Component.empty().decoration(TextDecoration.ITALIC, false)
    private val attributeRow = Regex("^\\s*[+-]?\\d+(?:\\.\\d+)?\\s+(Attack Damage|Attack Speed|attribute\\.name\\.(?:generic\\.)?attack_(?:damage|speed))\\s*$")
    private val unknownAttributeRow = Regex("^\\s+[+-]?\\d+(?:\\.\\d+)?\\s+.+$")
    private var failureReported = false

    fun replacement(original: ItemStack): ItemStack? {
        val meta = original.itemMeta ?: return null
        if (!meta.hasAttributeModifiers() || !meta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES)) return null
        val lore = normalizeLore(meta.lore() ?: return null) ?: return null
        val customItem = try {
            CustomStack.byItemStack(original) != null
        } catch (failure: Exception) {
            if (!failureReported) Logging.warn("ItemsAdder legacy attribute lore migration failed; original item preserved", failure)
            failureReported = true
            return null
        }
        if (!customItem) return null
        if (failureReported) {
            Logging.info("ItemsAdder legacy attribute lore migration recovered")
            failureReported = false
        }
        return original.clone().also { it.editMeta { copied -> copied.lore(lore) } }
    }

    internal fun normalizeLore(lore: List<Component>): List<Component>? {
        val sections = mutableListOf<Section>()
        var index = 0
        while (index < lore.size) {
            val header = plain.serialize(lore[index]).trim()
            if (header != MAIN_HAND && header != "When in Main Hand:") {
                index++
                continue
            }
            val attributes = linkedMapOf<String, Component>()
            var end = index + 1
            while (end < lore.size) {
                val row = lore[end]
                val label = attributeRow.matchEntire(plain.serialize(row))?.groupValues?.get(1) ?: break
                val key = if (label == "Attack Damage" || label.endsWith("attack_damage")) {
                    "attribute.name.attack_damage"
                } else "attribute.name.attack_speed"
                attributes[key] = translateLabel(row, label, key)
                end++
            }
            // A different attribute grammar belongs to its existing renderer, not this migration.
            if (end < lore.size && unknownAttributeRow.matches(plain.serialize(lore[end]))) return null
            if (attributes.isNotEmpty()) {
                sections += Section(
                    if (index > 0 && blank(lore[index - 1])) index - 1 else index,
                    index,
                    end,
                    containsTranslation(lore[index], MAIN_HAND),
                    attributes,
                )
            }
            index = end
        }
        if (sections.size < 2) return null
        // AE can flatten every native component back to English; duplicates still need migration.
        val canonical = sections.lastOrNull { it.translated } ?: sections.last()
        val attributes = linkedMapOf<String, Component>()
        // Current native rows win; retain a legacy speed row when an anvil refreshed only damage.
        sections.filterNot { it.translated }.forEach { attributes.putAll(it.attributes) }
        sections.filter { it.translated }.forEach { attributes.putAll(it.attributes) }
        val removed = BooleanArray(lore.size)
        sections.forEach { section -> for (row in section.start until section.end) removed[row] = true }
        val first = sections.first().start
        val next = (first until lore.size).firstOrNull { !removed[it] }
        return buildList {
            for (row in lore.indices) {
                if (row == first) {
                    if (lastOrNull()?.let(::blank) != true) add(gap)
                    add(translateLabel(lore[canonical.header], plain.serialize(lore[canonical.header]).trim(), MAIN_HAND))
                    listOf("attribute.name.attack_damage", "attribute.name.attack_speed").forEach { key ->
                        attributes[key]?.let(::add)
                    }
                    if (next != null && !blank(lore[next])) add(gap)
                }
                if (!removed[row]) add(lore[row])
            }
        }
    }

    private fun blank(row: Component): Boolean = plain.serialize(row).isBlank()

    private fun containsTranslation(row: Component, key: String): Boolean =
        row is TranslatableComponent && row.key() == key || row.children().any { containsTranslation(it, key) }

    private fun translateLabel(row: Component, old: String, key: String): Component {
        if (old == key && containsTranslation(row, key)) return row
        val translated = if (row is TranslatableComponent && row.key() == old) row.key(key) else row
        return translated.children(translated.children().map { translateLabel(it, old, key) })
            .replaceText { it.matchLiteral(old).replacement(Component.translatable(key)) }
    }

    private data class Section(
        val start: Int,
        val header: Int,
        val end: Int,
        val translated: Boolean,
        val attributes: Map<String, Component>,
    )
}
