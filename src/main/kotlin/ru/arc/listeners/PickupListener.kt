package ru.arc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import ru.arc.eliteloot.EliteLootManager
import ru.arc.eliteloot.presentEliteItem
import ru.arc.ARC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import ru.arc.hooks.HookRegistry

class PickupListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemPickup(event: PlayerAttemptPickupItemEvent) {
        if (HookRegistry.emHook == null) return
        val stack = event.item.itemStack
        val stack1 = EliteLootManager.eliteLootProcessor?.processEliteLoot(stack)
        if (stack1 != null && stack1 !== stack) {
            event.item.itemStack = stack1
        }
    }

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
        if (HookRegistry.emHook == null || !pending.add(player.uniqueId)) return
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            pending.remove(player.uniqueId)
            if (!player.isOnline || HookRegistry.emHook == null || !com.magmaguy.elitemobs.playerdata.database.PlayerData.isInMemory(player.uniqueId)) return@Runnable
            for (slot in 0 until player.inventory.size) {
                val original = player.inventory.getItem(slot) ?: continue
                val copy = original.clone()
                if (copy.itemMeta?.persistentDataContainer?.has(org.bukkit.NamespacedKey("arc", "dungeon_case_reward")) == true) {
                    EliteLootManager.eliteLootProcessor?.processEliteLoot(copy, caseReward = true)
                }
                val updated = presentEliteItem(copy, player)
                if (!updated.isSimilar(original)) player.inventory.setItem(slot, updated)
            }
        })
    }
}
