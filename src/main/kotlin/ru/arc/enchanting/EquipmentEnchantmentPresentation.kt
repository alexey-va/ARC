package ru.arc.enchanting

import net.advancedplugins.ae.api.AEAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import ru.arc.dungeons.dungeonService
import ru.arc.util.Logging

/** Keeps AE's native rows intact: its upgrade/removal paths recognize their exact prefix. */
internal object EquipmentEnchantmentPresentation {
    private val recordKey = NamespacedKey("arc", "equipment_enchantment_presentation")
    private val rowsKey = NamespacedKey("arc", "rows")
    private val hideKey = NamespacedKey("arc", "hide_enchants")
    private val styleKey = NamespacedKey("arc", "tooltip_style")
    private val beforeGapKey = NamespacedKey("arc", "before_gap")
    private val afterGapKey = NamespacedKey("arc", "after_gap")
    private val middleGapKey = NamespacedKey("arc", "middle_gap")
    private val commonStyle = NamespacedKey("lzblocks", "tooltip/common")
    private const val COUNTER_ICON_TRANSLATION = "ruscrafting.item_lore.icon"
    private val playerKillsKey = NamespacedKey("advancedenchantments", "stattrak")
    private val mobKillsKey = NamespacedKey("advancedenchantments", "mobtrak")
    private val soulsKey = NamespacedKey("advancedenchantments", "souls")
    private val slotsKey = NamespacedKey("advancedenchantments", "slots")
    private val orbKey = NamespacedKey("advancedenchantments", "increasedorb")
    private val legacy = LegacyComponentSerializer.legacySection()
    private val plain = PlainTextComponentSerializer.plainText()
    // Existing minecraft:default providers in the distributed RusCrafting pack.
    private val advancedIcons = setOf('', '', '', '')
    private val romanLevel = Regex("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")
    private data class AdvancedRows(val lines: List<Component>, val legacyDisplays: Map<String, String> = emptyMap())
    private var vanillaTitle: Component = Component.text("Зачарования", NamedTextColor.AQUA).decorate(TextDecoration.BOLD)
    private var advancedTitle: Component = Component.text("Дополнительные чары", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD)
    private var failureLogged = false

    fun configure(config: EnchantingConfig) {
        vanillaTitle = config.text("equipment.vanilla-heading")
        advancedTitle = config.text("equipment.advanced-heading")
    }

    fun ordinary(item: ItemStack): ItemStack {
        if (item.type.maxDurability <= 0 || item.maxStackSize != 1 || item.amount != 1 || item.type.isAir) return item
        val meta = item.itemMeta ?: return item
        val owned = meta.persistentDataContainer.has(recordKey)
        val namespaces = meta.persistentDataContainer.keys.mapTo(hashSetOf()) { it.namespace }
        if (!owned && !meta.hasEnchants() && "advancedenchantments" !in namespaces) return item
        // Existing custom presentation and hidden native stats retain their own owner.
        if (!owned && (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS) || meta.hasCustomModelData() || meta.hasItemModel())) return item
        if (namespaces.any { it in setOf("elitemobs", "magmacore", "itemsadder", "slimefun") } ||
            dungeonService()?.isEliteItem(item) == true) return item
        val advanced = advancedRows(item) ?: return item
        val vanilla = meta.enchants.entries.sortedBy { it.key.key.toString() }.map { (enchantment, level) ->
            Component.text(" ", NamedTextColor.WHITE)
                .append(enchantment.displayName(level))
                .decoration(TextDecoration.ITALIC, false)
        }
        return render(item, vanilla, advanced.lines, ordinary = true, beforeLore = null, legacyDisplays = advanced.legacyDisplays)
    }

    /** Called after EliteMobs has rebuilt its lore; item enchantment PDC remains authoritative. */
    fun advanced(item: ItemStack, beforeLore: String?): ItemStack {
        val rows = advancedRows(item) ?: return item
        return render(item, emptyList(), rows.lines, ordinary = false, beforeLore = beforeLore, legacyDisplays = rows.legacyDisplays)
    }

    private fun advancedRows(item: ItemStack): AdvancedRows? {
        if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            return if (item.itemMeta?.persistentDataContainer?.keys?.any { it.namespace == "advancedenchantments" } == true) null else AdvancedRows(emptyList())
        }
        return try {
            val legacyDisplays = mutableMapOf<String, String>()
            val rows = AEAPI.getEnchantmentsOnItem(item).entries
                .sortedWith { first, second ->
                    AEAPI.compareRarity(first.key, second.key).takeIf { it != 0 }
                        ?: first.key.compareTo(second.key)
                }
                .flatMap { (id, level) ->
                    AEAPI.getEnchantLore(id, level).orEmpty().mapIndexed { index, line ->
                        val row = legacy.deserialize(line).decoration(TextDecoration.ITALIC, false)
                        // Only the native display row for a current PDC enchant is a migration candidate.
                        // Description rows and unknown/custom prefixes retain their original contract.
                        if (index == 0 && plain.serialize(row).firstOrNull() in advancedIcons) {
                            val encoded = legacy.serialize(row)
                            val prefix = "§f${plain.serialize(row).first()} "
                            if (encoded.startsWith(prefix)) legacyDisplays[encoded] = encoded.removePrefix(prefix)
                        }
                        row
                    }
                }
            failureLogged = false
            AdvancedRows(rows, legacyDisplays)
        } catch (failure: Exception) {
            reportFailure(failure)
            null
        } catch (failure: LinkageError) {
            reportFailure(failure)
            null
        }
    }

    private fun reportFailure(failure: Throwable) {
        if (failureLogged) return
        failureLogged = true
        Logging.error("Equipment enchantment presentation failed; original item retained", failure)
    }

    internal fun render(
        item: ItemStack,
        vanilla: List<Component>,
        advanced: List<Component>,
        ordinary: Boolean,
        beforeLore: String?,
        legacyDisplays: Map<String, String> = emptyMap(),
    ): ItemStack {
        val meta = item.itemMeta ?: return item
        val previous = meta.persistentDataContainer.get(recordKey, PersistentDataType.TAG_CONTAINER)
        val lore = meta.lore().orEmpty()
        val serialized = meta.getLore().orEmpty()
        val oldRows = previous?.get(rowsKey, PersistentDataType.LIST.strings()).orEmpty()
        // Preserve authored color, hover metadata and suffixes on existing native AE rows.
        val available = lore.toMutableList()
        val matchedAdvanced = mutableListOf<Component>()
        fun takeMatching(predicate: (Component) -> Boolean): Component? {
            val index = available.indexOfFirst(predicate)
            return if (index < 0) null else available.removeAt(index).also(matchedAdvanced::add)
        }
        val retainedAdvanced = advanced.map { row ->
            val expected = plain.serialize(row)
            val native = takeMatching {
                val actual = plain.serialize(it)
                actual == expected || actual.startsWith("$expected ")
            }
            val oldDisplay = legacyDisplays[legacy.serialize(row)]
            if (oldDisplay == null) native ?: row else {
                val old = takeMatching {
                    val encoded = legacy.serialize(it)
                    encoded == oldDisplay || (native == null && encoded.startsWith(oldDisplay) &&
                        plain.serialize(legacy.deserialize(encoded.removePrefix(oldDisplay))).startsWith(" "))
                }
                // A native upgrade before the first refresh can leave its previous unprefixed level.
                // Remove only an exact native-styled name + valid Roman level, with no authored suffix.
                val level = oldDisplay.substringAfterLast(' ')
                if (level.isNotEmpty() && romanLevel.matches(level)) {
                    val stem = oldDisplay.substringBeforeLast(' ') + " "
                    takeMatching {
                        val encoded = legacy.serialize(it)
                        val candidate = encoded.removePrefix(stem)
                        encoded.startsWith(stem) && candidate.isNotEmpty() && romanLevel.matches(candidate)
                    }
                }
                native ?: old?.let { Component.text(expected.take(2), NamedTextColor.WHITE).append(it) } ?: row
            }
        }
        var body = removeRows(lore, serialized, oldRows,
            previous?.get(beforeGapKey, PersistentDataType.BOOLEAN) == true,
            previous?.get(afterGapKey, PersistentDataType.BOOLEAN) == true,
            previous?.get(middleGapKey, PersistentDataType.BOOLEAN) == true)
        // Fresh native AE rows may have been prepended by its apply/organize operation.
        body = removeRows(body, body.map(legacy::serialize), matchedAdvanced.map(legacy::serialize))
        val generated = buildList {
            if (vanilla.isNotEmpty()) { add(vanillaTitle); addAll(vanilla) }
            if (advanced.isNotEmpty()) {
                if (isNotEmpty()) add(Component.empty())
                add(advancedTitle)
                addAll(retainedAdvanced)
            }
        }.map { it.decoration(TextDecoration.ITALIC, false) }
        val ownsHide = ordinary && (previous?.get(hideKey, PersistentDataType.BOOLEAN) == true ||
            (vanilla.isNotEmpty() && !meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)))
        val ownsStyle = ordinary && (meta.tooltipStyle == null ||
            (previous?.get(styleKey, PersistentDataType.BOOLEAN) == true && meta.tooltipStyle == commonStyle))
        val insertion = if (ordinary) 0 else body.indexOfFirst { plain.serialize(it).trim() == beforeLore }.takeIf { it >= 0 } ?: body.size
        val combined = body.take(insertion).toMutableList()
        val beforeGap = generated.isNotEmpty() && (if (ordinary) combined.isEmpty() else combined.lastOrNull()?.let { !blank(it) } == true)
        if (beforeGap) combined += Component.empty()
        val generatedStart = combined.size
        combined += generated
        val generatedEnd = combined.size
        val after = body.drop(insertion)
        val afterGap = generated.isNotEmpty() && after.firstOrNull()?.let { !blank(it) } == true
        if (afterGap) combined += Component.empty()
        combined += after
        val tags = meta.persistentDataContainer
        val withIcons = combined.map { withCounterIcon(it, tags) }
        if (generated.isEmpty() && previous == null && withIcons == lore) return item
        meta.lore(combined.takeIf { it.isNotEmpty() })
        if (withIcons != combined) {
            val nativeLore = meta.getLore()
            meta.lore(withIcons)
            if (meta.getLore() != nativeLore) meta.lore(combined)
        }
        if (ownsHide) {
            if (vanilla.isNotEmpty()) meta.addItemFlags(ItemFlag.HIDE_ENCHANTS) else meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS)
        }
        if (ownsStyle) meta.tooltipStyle = if (generated.isNotEmpty()) commonStyle else null
        if (generated.isEmpty()) {
            meta.persistentDataContainer.remove(recordKey)
        } else {
            val record = meta.persistentDataContainer.adapterContext.newPersistentDataContainer()
            // Legacy snapshots also match after AE has flattened Adventure components through getLore/setLore.
            val ownedRows = meta.getLore().orEmpty().subList(generatedStart, generatedEnd)
                .filter { plain.serialize(legacy.deserialize(it)).isNotBlank() }
            record.set(rowsKey, PersistentDataType.LIST.strings(), ownedRows)
            record.set(hideKey, PersistentDataType.BOOLEAN, ownsHide)
            record.set(styleKey, PersistentDataType.BOOLEAN, ownsStyle)
            record.set(beforeGapKey, PersistentDataType.BOOLEAN, beforeGap)
            record.set(afterGapKey, PersistentDataType.BOOLEAN, afterGap)
            record.set(middleGapKey, PersistentDataType.BOOLEAN, vanilla.isNotEmpty() && advanced.isNotEmpty())
            meta.persistentDataContainer.set(recordKey, PersistentDataType.TAG_CONTAINER, record)
        }
        return item.clone().also { it.itemMeta = meta }
    }

    private fun withCounterIcon(row: Component, tags: PersistentDataContainer): Component {
        if ((row.children().firstOrNull() as? TranslatableComponent)?.key() == COUNTER_ICON_TRANSLATION) return row
        val line = plain.serialize(row)
        val glyph = when {
            line.startsWith("Убийств: ") &&
                tags.get(playerKillsKey, PersistentDataType.STRING)?.let { line == "Убийств: $it" } == true -> ""
            line.startsWith("Мобов: ") &&
                tags.get(mobKillsKey, PersistentDataType.STRING)?.let { line == "Мобов: $it" } == true -> ""
            line.startsWith("Собрано Душ: ") &&
                tags.get(soulsKey, PersistentDataType.INTEGER)?.let { line == "Собрано Душ: $it" } == true -> ""
            line.startsWith("Осталось зачарований: ") && tags.has(slotsKey, PersistentDataType.INTEGER) &&
                line.substringAfter(": ").toIntOrNull() != null -> ""
            " Слотов Зачарования (Сфера [+" in line &&
                tags.get(orbKey, PersistentDataType.INTEGER)?.let { line.endsWith("[+$it])") } == true -> ""
            line == "ЗАЩИЩЕНО" && legacy.serialize(row) == "§f§lЗАЩИЩЕНО" -> ""
            else -> return row
        }
        // The client pack translates the prefix to the glyph and a space. The server's
        // empty fallback preserves the exact legacy text AE uses to update counters and protection.
        val icon = Component.text(glyph, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
        val prefix = Component.translatable(COUNTER_ICON_TRANSLATION, "", icon)
        return Component.empty().append(prefix).append(row)
    }

    private fun removeRows(
        lines: List<Component>, serialized: List<String>, owned: List<String>,
        beforeGap: Boolean = false, afterGap: Boolean = false, middleGap: Boolean = false,
    ): List<Component> {
        val remaining = owned.groupingBy { it }.eachCount().toMutableMap()
        val removed = lines.indices.filter { index ->
            val key = serialized.getOrNull(index)
            val count = remaining[key] ?: 0
            if (count == 0) false else { remaining[key!!] = count - 1; true }
        }.toMutableSet()
        val first = serialized.indexOf(owned.firstOrNull()).takeIf { it >= 0 }
        val last = removed.maxOrNull()
        if (first != null && last != null) {
            if (beforeGap && first > 0 && blank(lines[first - 1])) removed += first - 1
            if (afterGap && last < lines.lastIndex && blank(lines[last + 1])) removed += last + 1
            if (middleGap) (first..last).firstOrNull { index ->
                blank(lines[index]) && index - 1 in removed && index + 1 in removed
            }?.let { removed += it }
        }
        return lines.filterIndexed { index, _ -> index !in removed }
    }

    private fun blank(line: Component): Boolean = plain.serialize(line).isBlank()
}
