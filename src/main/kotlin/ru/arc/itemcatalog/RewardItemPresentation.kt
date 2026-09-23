package ru.arc.itemcatalog

import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.ItemStack
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import ru.arc.util.TextUtil

/** Applies authored identity only to curated collection prizes, never to quantity labels on case cards. */
internal object RewardItemPresentation {
    /** Style-only path for native consumables and bearer vouchers: retain their functional identity. */
    fun tooltip(stack: ItemStack, entry: RewardCatalogEntry): ItemStack = stack.clone().also { result ->
        entry.tooltipStyle?.let { style ->
            result.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key(style))
        }
    }

    fun apply(stack: ItemStack, entry: RewardCatalogEntry): ItemStack = tooltip(stack, entry).also { result ->
        result.editMeta { meta ->
            entry.name?.let { meta.displayName(TextUtil.mm(it, true).decoration(TextDecoration.ITALIC, false)) }
            val authored = entry.description.map { TextUtil.mm(it, true).decoration(TextDecoration.ITALIC, false) }
            val native = meta.lore().orEmpty()
            meta.lore((authored + native).distinct())
        }
    }
}
