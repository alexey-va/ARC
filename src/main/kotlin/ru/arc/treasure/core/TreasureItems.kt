package ru.arc.treasure.core

import org.bukkit.inventory.ItemStack

/**
 * Converts a held Bukkit item into the durable treasure representation.
 *
 * Slimefun items must be stored by their registry ID. Serializing their Bukkit
 * ItemStack would freeze implementation NBT into the pool and can break after
 * an addon or Slimefun update.
 */
object TreasureItems {
    fun fromStack(
        stack: ItemStack,
        amount: Int = stack.amount,
        weight: Int = 1,
        slimefunId: (ItemStack) -> String? = { null },
    ): Treasure {
        val safeAmount = amount.coerceAtLeast(1)
        val sfId = slimefunId(stack)?.trim()?.takeIf { it.isNotEmpty() }
        return if (sfId != null) {
            Treasure.Slimefun(
                itemId = sfId,
                min = safeAmount,
                max = safeAmount,
                weight = weight,
            )
        } else {
            Treasure.Item(
                stack = stack.clone().apply { this.amount = 1 },
                min = safeAmount,
                max = safeAmount,
                weight = weight,
            )
        }
    }
}
