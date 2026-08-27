package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.event.events.sell.AuctionPreSellEvent
import fr.maxlego08.zauctionhouse.api.event.events.RuleLoadEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveExpiredItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent
import fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemovePurchasedItemEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.buildertools.BuilderToolsModule
import ru.arc.contracts.PaperSeasonTrophyItems

internal class AuctionTrophyGuardListener(
    private val hook: AuctionHook,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPreSell(event: AuctionPreSellEvent) {
        if (PaperSeasonTrophyItems.isBoundTrophy(event.itemStack)) {
            event.isCancelled = true
            return
        }
        if (hook.isBlockedBuilderBook(event.itemStack)) {
            event.isCancelled = true
            BuilderToolsModule.rejectUnsafeAuctionSale(event.player)
        }
    }

    /** zAuctionHouse 4.0.1.2 replaces its rule sets after firing this event. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRulesLoaded(@Suppress("UNUSED_PARAMETER") event: RuleLoadEvent) {
        Bukkit.getScheduler().runTask(ARC.instance, Runnable(hook::ensureBuilderBookRule))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onListedRemoved(event: AuctionRemoveListedItemEvent) {
        hook.onBuilderBookDelivered(event.player, event.item)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onExpiredRemoved(event: AuctionRemoveExpiredItemEvent) {
        hook.onBuilderBookDelivered(event.player, event.item)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPurchasedRemoved(event: AuctionRemovePurchasedItemEvent) {
        hook.onBuilderBookDelivered(event.player, event.item)
    }
}
