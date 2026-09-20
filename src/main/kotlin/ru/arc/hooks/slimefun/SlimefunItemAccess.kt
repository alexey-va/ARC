@file:Suppress("DEPRECATION") // Non-tile addon blocks still use legacy Slimefun storage.

package ru.arc.hooks.slimefun

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import me.mrCookieSlime.Slimefun.api.BlockStorage
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack

/** Read-only item identity access, independent of backpack listeners and synchronization. */
object SlimefunItemAccess {
    fun blockId(block: Block): String? = resolveSlimefunBlockId(
        primary = { Slimefun.getBlockDataService().getBlockData(block).orElse(null) },
        legacy = { BlockStorage.checkID(block) },
    )

    fun itemStack(id: String): ItemStack? = SlimefunItem.getById(id)?.item
}
