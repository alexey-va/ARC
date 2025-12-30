package ru.arc.util

import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

object ItemUtils {

    @JvmStatic
    fun split(stack: ItemStack?, count: Int): List<ItemStack> {
        val stacks = mutableListOf<ItemStack>()
        if (stack == null) {
            Logging.warn("Stack in split is null!")
            return stacks
        }
        val maxStack = stack.maxStackSize

        var remaining = count
        while (remaining > 0) {
            val qty = minOf(remaining, maxStack)
            val i = stack.asQuantity(qty)
            stacks.add(i)
            remaining -= qty
        }

        return stacks
    }

    @JvmStatic
    fun connectedChests(block: Block): List<Block> {
        val blocks = mutableSetOf<Block>()
        val state = block.state
        if (state is Barrel) {
            blocks.add(block)
        }
        if (state is DoubleChest) {
            val left = state.leftSide as? Chest
            val right = state.rightSide as? Chest
            if (left != null) blocks.add(left.block)
            if (right != null) blocks.add(right.block)
        }
        if (state is Chest) {
            blocks.add(block)
        }
        return ArrayList(blocks)
    }

    @JvmStatic
    fun extractItems(block: Block): List<ItemStack> {
        val inventory = extractInventory(block)
        return inventory?.contents?.filterNotNull() ?: emptyList()
    }

    @JvmStatic
    fun extractInventory(block: Block): Inventory? {
        return when (val state = block.state) {
            is DoubleChest -> state.inventory
            is Chest -> state.inventory
            is Barrel -> state.inventory
            else -> null
        }
    }
}

