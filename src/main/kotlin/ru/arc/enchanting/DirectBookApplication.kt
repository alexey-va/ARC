package ru.arc.enchanting

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Commit an already prepared result in place, consuming one book without opening an inventory. */
internal fun applyBookInInventory(
    player: Player,
    targetSlot: Int,
    expectedTarget: ItemStack,
    expectedBook: ItemStack,
    result: ItemStack?,
    bookInventorySlot: Int? = null,
) {
    require(expectedTarget.amount == 1 && expectedBook.amount > 0)
    require(bookInventorySlot == null || bookInventorySlot != targetSlot)
    fun currentBook() = if (bookInventorySlot == null) player.itemOnCursor else player.inventory.getItem(bookInventorySlot)
    fun setBook(value: ItemStack?) {
        if (bookInventorySlot == null) player.setItemOnCursor(value)
        else player.inventory.setItem(bookInventorySlot, value)
    }
    check(enchantmentInputsMatch(expectedTarget, expectedBook, player.inventory.getItem(targetSlot), currentBook())) {
        "Enchantment inputs changed before application"
    }
    val remainingBook = expectedBook.takeIf { it.amount > 1 }?.clone()?.apply { amount-- }
    val replacement = result?.clone()?.apply { amount = 1 }
    var bookWriteAttempted = false
    try {
        player.inventory.setItem(targetSlot, replacement)
        check(sameDirectStack(currentBook(), expectedBook)) {
            "Enchantment inputs changed during application"
        }
        bookWriteAttempted = true
        setBook(remainingBook)
    } catch (failure: Exception) {
        // Other listeners may decorate the result. Never mint a second target when its exact
        // identity changed; rollback only slots still containing this transaction's own writes.
        if (sameDirectStack(player.inventory.getItem(targetSlot), replacement)) {
            player.inventory.setItem(targetSlot, expectedTarget.clone())
        }
        if (bookWriteAttempted && sameDirectStack(player.inventory.getItem(targetSlot), expectedTarget) &&
            sameDirectStack(currentBook(), remainingBook)) setBook(expectedBook.clone())
        throw failure
    }
}

private fun sameDirectStack(first: ItemStack?, second: ItemStack?): Boolean =
    if (first == null || first.type.isAir || first.amount == 0) second == null || second.type.isAir || second.amount == 0
    else first == second

internal fun enchantmentInputsMatch(expectedTarget: ItemStack, expectedBook: ItemStack, target: ItemStack?, cursor: ItemStack?) =
    expectedTarget == target && expectedBook == cursor

/** Creative proposes a cursor stack; keep it in a server-owned slot before Paper clears DENY. */
internal fun admitCreativeBook(player: Player, book: ItemStack): Int? {
    if (book.amount !in 1..book.maxStackSize) return null
    player.inventory.addItem(book.clone()).values.forEach { player.world.dropItem(player.location, it) }
    return (0 until player.inventory.storageContents.size).firstOrNull {
        player.inventory.getItem(it)?.isSimilar(book) == true
    }
}
