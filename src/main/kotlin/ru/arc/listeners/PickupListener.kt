package ru.arc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.PrepareInventoryResultEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import ru.arc.enchanting.EquipmentEnchantmentPresentation

class PickupListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareResult(event: PrepareInventoryResultEvent) {
        val result = event.result ?: return
        val presented = EquipmentEnchantmentPresentation.ordinary(result)
        if (presented != result) event.result = presented
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            if (!event.enchanter.isOnline || event.enchanter.openInventory.topInventory !== event.inventory) return@Runnable
            val current = event.inventory.getItem(0) ?: return@Runnable
            val presented = EquipmentEnchantmentPresentation.ordinary(current)
            if (!presented.isSimilar(current)) event.inventory.setItem(0, presented)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        (event.player as? Player)?.let(::refreshLater)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let(::refreshLater)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSlotChange(event: PlayerInventorySlotChangeEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeldItemChange(event: PlayerItemHeldEvent) = refreshLater(event.player)

    private val pending = mutableSetOf<java.util.UUID>()

    private fun refreshLater(player: Player) {
        if (!pending.add(player.uniqueId)) return
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            pending.remove(player.uniqueId)
            if (!player.isOnline) return@Runnable
            val hasAdvancedEnchantments = Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
            val hasItemsAdder = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
            // One coalesced, main-thread pass over this player's fixed inventory; no container/world scans.
            for (slot in 0 until player.inventory.size) {
                val original = player.inventory.getItem(slot) ?: continue
                LegacyTreasureItems.replacement(original)?.let { replacement ->
                    player.inventory.setItem(slot, replacement)
                }
                if (hasItemsAdder) {
                    LegacyItemsAdderAttributes.replacement(original)?.let { replacement ->
                        player.inventory.setItem(slot, replacement)
                    }
                }
                if (hasAdvancedEnchantments && ru.arc.dungeons.api.ArcBookIdentity.isAdvancedEnchantmentsBook(original)) {
                    val updated = ru.arc.enchanting.presentAdvancedBook(original, player)
                    if (!updated.isSimilar(original)) player.inventory.setItem(slot, updated)
                    continue
                }
                val current = player.inventory.getItem(slot) ?: continue
                val presented = EquipmentEnchantmentPresentation.ordinary(current)
                if (!presented.isSimilar(current)) player.inventory.setItem(slot, presented)
            }
        })
    }
}
