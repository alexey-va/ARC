package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.AdventurersGuildConfig
import com.magmaguy.elitemobs.config.ItemSettingsConfig
import com.magmaguy.elitemobs.config.menus.premade.SkillBonusMenuConfig
import com.magmaguy.elitemobs.items.EliteItemLore
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

internal fun eliteTooltipTier(level: Int): String = when {
    level >= 100 -> "artifact"
    level >= 80 -> "legendary"
    level >= 60 -> "epic"
    level >= 40 -> "rare"
    level >= 20 -> "uncommon"
    else -> "common"
}

internal fun compactEliteLore(lines: List<Component>): List<Component> {
    val plain = PlainTextComponentSerializer.plainText()
    val result = mutableListOf<Component>()
    for (line in lines) {
        val empty = plain.serialize(line).isBlank()
        if (empty && (result.isEmpty() || plain.serialize(result.last()).isBlank())) continue
        result += line.decoration(TextDecoration.ITALIC, false)
    }
    while (result.isNotEmpty() && plain.serialize(result.last()).isBlank()) result.removeLast()
    return result
}

private val legacyEliteTextTranslations = mapOf(
    "Traveling Mallet" to "Походный молот",
    "Forward Scout Coat" to "Куртка передового разведчика",
    "Too heavy for a sensible pack." to "Слишком тяжёл для обычного рюкзака.",
    "A very sensible thing to have in a fight." to "Зато в бою без него никуда.",
    "Light straps leave room for a full stride." to "Лёгкие ремни не стесняют шага.",
    "The scouts always leave before breakfast." to "Разведчики всегда выходят до завтрака.",
)

internal fun localizeLegacyEliteText(component: Component): Component {
    val source = PlainTextComponentSerializer.plainText().serialize(component).trim()
    val translation = legacyEliteTextTranslations[source] ?: return component
    return Component.text(translation)
        .style(component.style())
        .decoration(TextDecoration.ITALIC, false)
}

private fun sanitizeEliteLore(lines: List<Component>): List<Component> {
    val plain = PlainTextComponentSerializer.plainText()
    return compactEliteLore(
        lines.asSequence()
            .filterNot { plain.serialize(it).contains("skillRequirement", ignoreCase = true) }
            .map(::localizeLegacyEliteText)
            .toList(),
    )
}

private val legacyAmpersandSerializer = LegacyComponentSerializer.legacyAmpersand()
private val legacySectionSerializer = LegacyComponentSerializer.legacySection()
private val plainEliteTextSerializer = PlainTextComponentSerializer.plainText()
private val magicEliteDamageLore = listOf(
    Component.text(" ", TextColor.color(0xFFFFFF))
        .append(Component.text("Магический урон по элитам", TextColor.color(0xFF716C)))
        .decoration(TextDecoration.ITALIC, false),
    Component.text("Зависит от уровня, навыка и заклинания", TextColor.color(0xB9C2D0))
        .decoration(TextDecoration.ITALIC, false),
)

private fun plainEliteText(text: String): String =
    plainEliteTextSerializer.serialize(legacyAmpersandSerializer.deserialize(
        plainEliteTextSerializer.serialize(legacySectionSerializer.deserialize(text)),
    ))

private val elitePresentationRootKey = NamespacedKey("elitemobs", "enchantment_presentation")
private val elitePresentationLinesKey = NamespacedKey("elitemobs", "lines")
private val elitePresentationPositionKey = NamespacedKey("elitemobs", "position")

internal fun reindexedEliteEnchantmentLorePosition(
    hostLore: List<String>,
    generatedLore: List<String>,
    recordedPosition: Int,
): Int? {
    val lastStart = hostLore.size - generatedLore.size
    if (recordedPosition in 0..lastStart &&
        hostLore.subList(recordedPosition, recordedPosition + generatedLore.size) == generatedLore
    ) return recordedPosition
    if (generatedLore.isEmpty() || lastStart < 0) return null

    val matches = (0..lastStart).filter { position ->
        hostLore.subList(position, position + generatedLore.size) == generatedLore
    }
    return matches.singleOrNull()
}

/** Keep EliteMobs' stored generated-lore range aligned after ARC inserts or compacts other rows. */
internal fun reindexEliteEnchantmentLore(meta: ItemMeta) {
    val container = meta.persistentDataContainer
        .get(elitePresentationRootKey, PersistentDataType.TAG_CONTAINER) ?: return
    val generatedLore = container.get(elitePresentationLinesKey, PersistentDataType.LIST.strings()) ?: return
    val recordedPosition = container.get(elitePresentationPositionKey, PersistentDataType.INTEGER) ?: return
    val correctedPosition = reindexedEliteEnchantmentLorePosition(
        meta.getLore().orEmpty(),
        generatedLore,
        recordedPosition,
    ) ?: return
    if (correctedPosition != recordedPosition) {
        container.set(elitePresentationPositionKey, PersistentDataType.INTEGER, correctedPosition)
        meta.persistentDataContainer.set(elitePresentationRootKey, PersistentDataType.TAG_CONTAINER, container)
    }
}

/** Native rendering can change other PDC, such as price; only transfer its lore ownership record. */
internal fun copyElitePresentationMetadata(source: ItemMeta, destination: ItemMeta) {
    val presentation = source.persistentDataContainer
        .get(elitePresentationRootKey, PersistentDataType.TAG_CONTAINER)
    if (presentation == null) {
        destination.persistentDataContainer.remove(elitePresentationRootKey)
    } else {
        destination.persistentDataContainer.set(elitePresentationRootKey, PersistentDataType.TAG_CONTAINER, presentation)
    }
}

/** Legacy EDPS measures physical attacks, while FMM magic damage depends on the level, skill and spell. */
internal fun replaceMagicEliteDpsLore(
    lines: List<Component>,
    isMagicWeapon: Boolean,
    weaponEntryTemplate: String,
): List<Component> {
    if (!isMagicWeapon) return lines
    val template = plainEliteText(weaponEntryTemplate)
    val placeholder = "\$EDPS"
    val marker = template.indexOf(placeholder)
    if (marker < 0 || template.indexOf(placeholder, marker + placeholder.length) >= 0) return lines

    val prefix = Regex.escape(template.substring(0, marker))
    val suffix = Regex.escape(template.substring(marker + placeholder.length))
    val nativeDpsRow = Regex("^$prefix(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)$suffix$")
    return lines.flatMap { line ->
        if (nativeDpsRow.matches(plainEliteTextSerializer.serialize(line))) {
            magicEliteDamageLore
        } else {
            listOf(line)
        }
    }
}

internal fun eliteGearRequirementLine(skillName: String, level: Int): Component =
    Component.text(" ", TextColor.color(0xFFFFFF))
        .append(
            if (level <= 20) {
                Component.text("Навык не требуется", TextColor.color(0x67E480))
            } else {
                Component.text("Требуется: ", TextColor.color(0xFF716C))
                    .append(Component.text(skillName, TextColor.color(0x68D8FF)))
                    .append(Component.text(" · ур. ", TextColor.color(0xF5C451)))
                    .append(Component.text(level, TextColor.color(0xFFFFFF)))
            },
        )
        .decoration(TextDecoration.ITALIC, false)

internal fun replaceEliteGearRequirement(
    lines: List<Component>,
    skillName: String,
    level: Int,
): List<Component> {
    val plain = PlainTextComponentSerializer.plainText()
    val result = sanitizeEliteLore(lines).filterNot { plain.serialize(it).trimStart().startsWith("") }.toMutableList()
    result.add(if (result.isEmpty()) 0 else 1, eliteGearRequirementLine(skillName, level))
    return result
}

private fun withEliteGearRequirement(item: ItemStack, lines: List<Component>): List<Component> {
    val presentedLines = replaceMagicEliteDpsLore(
        lines,
        WeaponIdentityResolver.isMagicWeapon(item),
        ItemSettingsConfig.getWeaponEntry(),
    )
    if (!AdventurersGuildConfig.isSkillBasedGearRestriction()) return sanitizeEliteLore(presentedLines)
    val skill = WeaponIdentityResolver.progressionSkillIncludingArmor(item) ?: return sanitizeEliteLore(presentedLines)
    return replaceEliteGearRequirement(
        presentedLines,
        SkillBonusMenuConfig.getSkillTypeDisplayName(skill),
        EliteItemManager.getRoundedItemLevel(item),
    )
}

/** Native lore is generated on a clone: recalculating its price must not rewrite the real item's PDC. */
internal fun presentEliteItem(
    item: ItemStack,
    viewer: Player,
    bookText: EliteEnchantmentBookPresentationText = ru.arc.enchanting.EnchantingModule.bookText,
): ItemStack {
    if (isAdvancedEnchantmentsBook(item)) return item
    if (isEliteEnchantmentBook(item)) return presentEliteEnchantmentBook(item, bookText)
    if (!EliteItemManager.isEliteMobsItem(item)) return item
    val meta = item.itemMeta
    val tooltipStyle = NamespacedKey("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(item))}")
    val owner = meta.persistentDataContainer.get(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING)
    if ((owner == null || owner == viewer.uniqueId.toString()) && !EliteEnchantmentItems.isEliteEnchantmentBook(item)) {
        val rendered = item.clone()
        rendered.editMeta(::reindexEliteEnchantmentLore)
        EliteItemLore(rendered, false)
        val renderedMeta = rendered.itemMeta
        copyElitePresentationMetadata(renderedMeta, meta)
        meta.lore(withEliteGearRequirement(rendered, renderedMeta.lore().orEmpty()))
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
    } else {
        meta.lore(
            replaceMagicEliteDpsLore(
                meta.lore().orEmpty(),
                WeaponIdentityResolver.isMagicWeapon(item),
                ItemSettingsConfig.getWeaponEntry(),
            ),
        )
    }
    meta.displayName()?.let { meta.displayName(localizeLegacyEliteText(it)) }
    meta.tooltipStyle = tooltipStyle
    meta.lore(sanitizeEliteLore(meta.lore().orEmpty()))
    reindexEliteEnchantmentLore(meta)
    item.itemMeta = meta
    return item
}

/** Prepare the entity stack before its first client update; preserve the native generated lore and owner. */
internal fun prepareEliteDrop(
    item: ItemStack,
    processor: EliteLootProcessor? = EliteLootManager.eliteLootProcessor,
    bookText: EliteEnchantmentBookPresentationText = ru.arc.enchanting.EnchantingModule.bookText,
): ItemStack {
    if (isAdvancedEnchantmentsBook(item)) return item
    if (!EliteItemManager.isEliteMobsItem(item) && !isEliteEnchantmentBook(item)) return item
    val prepared = item.clone()
    processor?.processEliteLoot(prepared)
    if (isEliteEnchantmentBook(prepared)) return presentEliteEnchantmentBook(prepared, bookText)
    prepared.editMeta { meta ->
        meta.displayName()?.let { meta.displayName(localizeLegacyEliteText(it)) }
        meta.lore(withEliteGearRequirement(prepared, meta.lore().orEmpty()))
        reindexEliteEnchantmentLore(meta)
    }
    prepared.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_STYLE,
        net.kyori.adventure.key.Key.key("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(prepared))}"))
    return prepared
}
