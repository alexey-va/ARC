package ru.arc.hooks.slimefun

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import io.github.thebusybiscuit.slimefun4.implementation.items.VanillaItem
import io.github.thebusybiscuit.slimefun4.implementation.items.misc.SyntheticEmerald
import io.papermc.paper.event.player.PlayerTradeEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantInventory

/** Checks the actual payment before consumption, including trade autofill and shift-clicks. */
class SlimefunTradeListener(
    private val isProtectedItem: (ItemStack) -> Boolean = { stack ->
        val item = SlimefunItem.getByItem(stack)
        item != null && item !is VanillaItem && item !is SyntheticEmerald
    },
    private val notifyPlayer: (Player) -> Unit = { player ->
        Slimefun.getLocalization().sendMessage(player, "villagers.no-trading", true)
    },
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTrade(event: PlayerTradeEvent) {
        val inventory = event.player.openInventory.topInventory as? MerchantInventory ?: return
        if ((0..1).none { slot -> inventory.getItem(slot)?.let(isProtectedItem) == true }) return
        event.isCancelled = true
        notifyPlayer(event.player)
    }
}
