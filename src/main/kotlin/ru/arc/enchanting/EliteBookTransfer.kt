package ru.arc.enchanting

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Moves one book (cursor or inventory) and one target into EM custody; failed opens restore both. */
internal fun transferEliteBookToConfirmation(
    player: Player,
    playerSlot: Int,
    target: ItemStack,
    book: ItemStack,
    itemSlot: Int,
    bookSlot: Int,
    bookInventorySlot: Int? = null,
    openNativeMenu: () -> Inventory,
): Inventory {
    fun currentBook() = if (bookInventorySlot == null) player.itemOnCursor else player.inventory.getItem(bookInventorySlot)
    fun setBook(item: ItemStack?) {
        if (bookInventorySlot == null) player.setItemOnCursor(item)
        else player.inventory.setItem(bookInventorySlot, item)
    }
    check(bookInventorySlot == null || bookInventorySlot != playerSlot)
    check(enchantmentInputsMatch(target, book, player.inventory.getItem(playerSlot), currentBook())) {
        "Enchantment inputs changed before transfer"
    }
    var receiving: Inventory? = null
    var insertedTarget = false
    var insertedBook = false
    try {
        setBook(remainder(book))
        player.inventory.setItem(playerSlot, remainder(target))
        val menu = openNativeMenu()
        check(itemSlot in 0 until menu.size && bookSlot in 0 until menu.size && itemSlot != bookSlot)
        check(menu.getItem(itemSlot).isEmptyItem() && menu.getItem(bookSlot).isEmptyItem()) {
            "Native EliteMobs input slots are occupied"
        }
        receiving = menu
        menu.setItem(itemSlot, single(target))
        insertedTarget = true
        menu.setItem(bookSlot, single(book))
        insertedBook = true
        return menu
    } catch (failure: Exception) {
        if (insertedTarget) receiving?.clear(itemSlot)
        if (insertedBook) receiving?.clear(bookSlot)
        // A cancelled open can run other listeners. Do not overwrite a changed cursor or slot.
        if (sameStack(player.inventory.getItem(playerSlot), target)) {
            // The target was not removed (or another open listener already restored it).
        } else if (sameStack(player.inventory.getItem(playerSlot), remainder(target))) {
            player.inventory.setItem(playerSlot, target.clone())
        } else returnItem(player, single(target))
        if (sameStack(currentBook(), book)) {
            // No cursor refund is needed.
        } else if (sameStack(currentBook(), remainder(book))) setBook(book.clone())
        else returnItem(player, single(book))
        throw failure
    }
}

internal fun enchantmentInputsMatch(expectedTarget: ItemStack, expectedBook: ItemStack, target: ItemStack?, cursor: ItemStack?) =
    expectedTarget == target && expectedBook == cursor

private fun single(item: ItemStack) = item.clone().apply { amount = 1 }
private fun remainder(item: ItemStack): ItemStack? = item.takeIf { it.amount > 1 }?.clone()?.apply { amount-- }
private fun ItemStack?.isEmptyItem() = this == null || type.isAir || amount <= 0
private fun sameStack(first: ItemStack?, second: ItemStack?) =
    if (first.isEmptyItem()) second.isEmptyItem() else first == second

private fun returnItem(player: Player, item: ItemStack) {
    player.inventory.addItem(item).values.forEach { player.world.dropItem(player.location, it) }
}
