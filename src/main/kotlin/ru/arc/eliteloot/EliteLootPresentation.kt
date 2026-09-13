package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.AdventurersGuildConfig
import com.magmaguy.elitemobs.config.menus.premade.SkillBonusMenuConfig
import com.magmaguy.elitemobs.items.EliteItemLore
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import com.magmaguy.elitemobs.skills.WeaponIdentityResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
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
    if (!AdventurersGuildConfig.isSkillBasedGearRestriction()) return sanitizeEliteLore(lines)
    val skill = WeaponIdentityResolver.progressionSkillIncludingArmor(item) ?: return sanitizeEliteLore(lines)
    return replaceEliteGearRequirement(
        lines,
        SkillBonusMenuConfig.getSkillTypeDisplayName(skill),
        EliteItemManager.getRoundedItemLevel(item),
    )
}

/** Native lore is generated on a clone: recalculating its price must not rewrite the real item's PDC. */
internal fun presentEliteItem(item: ItemStack, viewer: Player): ItemStack {
    if (!EliteItemManager.isEliteMobsItem(item)) return item
    val meta = item.itemMeta
    meta.displayName()?.let { meta.displayName(localizeLegacyEliteText(it)) }
    meta.tooltipStyle = NamespacedKey("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(item))}")
    val owner = meta.persistentDataContainer.get(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING)
    if ((owner == null || owner == viewer.uniqueId.toString()) && !EliteEnchantmentItems.isEliteEnchantmentBook(item)) {
        val rendered = item.clone()
        EliteItemLore(rendered, false)
        meta.lore(withEliteGearRequirement(rendered, rendered.itemMeta.lore().orEmpty()))
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
    }
    meta.lore(sanitizeEliteLore(meta.lore().orEmpty()))
    item.itemMeta = meta
    return item
}

/** Prepare the entity stack before its first client update; preserve the native generated lore and owner. */
internal fun prepareEliteDrop(item: ItemStack, processor: EliteLootProcessor? = EliteLootManager.eliteLootProcessor): ItemStack {
    if (!EliteItemManager.isEliteMobsItem(item)) return item
    val prepared = item.clone()
    processor?.processEliteLoot(prepared)
    prepared.editMeta { meta ->
        meta.displayName()?.let { meta.displayName(localizeLegacyEliteText(it)) }
        meta.lore(withEliteGearRequirement(prepared, meta.lore().orEmpty()))
    }
    prepared.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_STYLE,
        net.kyori.adventure.key.Key.key("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(prepared))}"))
    return prepared
}
