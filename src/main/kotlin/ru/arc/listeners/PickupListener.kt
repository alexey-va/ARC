package ru.arc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent

class PickupListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        (event.player as? Player)?.let(::refreshLater)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSlotChange(event: PlayerInventorySlotChangeEvent) = refreshLater(event.player)

    private val pending = mutableSetOf<java.util.UUID>()

    private fun refreshLater(player: Player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") ||
            !pending.add(player.uniqueId)) return
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            pending.remove(player.uniqueId)
            if (!player.isOnline) return@Runnable
            for (slot in 0 until player.inventory.size) {
                val original = player.inventory.getItem(slot) ?: continue
                if (ru.arc.dungeons.api.ArcBookIdentity.isAdvancedEnchantmentsBook(original)) {
                    val updated = ru.arc.enchanting.presentAdvancedBook(original, player)
                    if (!updated.isSimilar(original)) player.inventory.setItem(slot, updated)
                    continue
                }

            }
        })
    }
}
