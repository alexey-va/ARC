package ru.arc.enchanting

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.dungeons.api.ArcDungeonItems

private fun items() = requireNotNull(Bukkit.getServicesManager().load(ArcDungeonItems::class.java)) {
    "ARC item service is unavailable"
}
internal fun readAdvancedMagicDust(item: ItemStack) = items().readMagicDust(item)
internal fun presentAdvancedBook(item: ItemStack, viewer: Player) = items().presentAdvancedBook(item, viewer)
internal fun admitCreativeBook(player: Player, book: ItemStack): Int? = items().admitCreativeBook(player, book)
internal fun applyBookInInventory(player: Player, targetSlot: Int, expectedTarget: ItemStack, expectedBook: ItemStack,
    result: ItemStack?, bookInventorySlot: Int? = null) =
    items().applyBook(player, targetSlot, expectedTarget, expectedBook, result, bookInventorySlot)
internal fun enchantmentInputsMatch(expectedTarget: ItemStack, expectedBook: ItemStack, target: ItemStack?, cursor: ItemStack?) =
    expectedTarget == target && expectedBook == cursor
