package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.event.events.sell.AuctionPreSellEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.contracts.api.ArcContractTrophyMarkers

internal class AuctionTrophyGuardListener : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPreSell(event: AuctionPreSellEvent) {
        if (ArcContractTrophyMarkers.isBound(event.itemStack)) {
            event.isCancelled = true
        }
    }
}
