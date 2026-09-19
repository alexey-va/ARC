package ru.arc.itemcatalog

import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.ItemStack
import ru.arc.util.TextUtil

/** Applies authored identity only to curated collection prizes, never to quantity labels on case cards. */
internal object RewardItemPresentation {
    fun apply(stack: ItemStack, entry: RewardCatalogEntry): ItemStack = stack.clone().also { result ->
        result.editMeta { meta ->
            entry.name?.let { meta.displayName(TextUtil.mm(it, true).decoration(TextDecoration.ITALIC, false)) }
            val authored = entry.description.map { TextUtil.mm(it, true).decoration(TextDecoration.ITALIC, false) }
            val native = meta.lore().orEmpty()
            meta.lore((authored + native).distinct())
        }
    }
}
