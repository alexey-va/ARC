package ru.arc.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import ru.arc.eliteloot.EliteLootManager
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
}
