package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.items.EliteItemLore
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import net.kyori.adventure.text.Component
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

/** Native lore is generated on a clone: recalculating its price must not rewrite the real item's PDC. */
internal fun presentEliteItem(item: ItemStack, viewer: Player): ItemStack {
    if (!EliteItemManager.isEliteMobsItem(item)) return item
    val meta = item.itemMeta
    meta.tooltipStyle = NamespacedKey("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(item))}")
    val owner = meta.persistentDataContainer.get(NamespacedKey("elitemobs", "soulbind"), PersistentDataType.STRING)
    if ((owner == null || owner == viewer.uniqueId.toString()) && !EliteEnchantmentItems.isEliteEnchantmentBook(item)) {
        val rendered = item.clone()
        EliteItemLore(rendered, false)
        meta.lore(compactEliteLore(rendered.itemMeta.lore().orEmpty()))
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
    }
    meta.lore(compactEliteLore(meta.lore().orEmpty()))
    item.itemMeta = meta
    return item
}

/** Prepare the entity stack before its first client update; preserve the native generated lore and owner. */
internal fun prepareEliteDrop(item: ItemStack, processor: EliteLootProcessor? = EliteLootManager.eliteLootProcessor): ItemStack {
    if (!EliteItemManager.isEliteMobsItem(item)) return item
    val prepared = item.clone()
    processor?.processEliteLoot(prepared)
    prepared.editMeta { meta -> meta.lore(compactEliteLore(meta.lore().orEmpty())) }
    prepared.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_STYLE,
        net.kyori.adventure.key.Key.key("lzblocks", "tooltip/${eliteTooltipTier(EliteItemManager.getRoundedItemLevel(prepared))}"))
    return prepared
}
