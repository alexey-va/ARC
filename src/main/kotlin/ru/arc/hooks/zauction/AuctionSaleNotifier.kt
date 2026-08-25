package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent
import fr.maxlego08.zauctionhouse.api.item.ItemStatus
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

internal class AuctionSaleNotifier(private val hook: AuctionHook) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onListedItemRemoved(event: AuctionRemoveListedItemEvent) {
        if (event.item.status != ItemStatus.PURCHASED) return
        val sale = hook.saleEvent(event.item) ?: return
        hook.auctionMessager?.sendSale(sale)
    }
}
