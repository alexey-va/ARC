package ru.arc.hooks.citizens

import net.citizensnpcs.api.event.NPCRightClickEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.autobuild.BuildingManager

class CitizensListener : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onNpcClick(event: NPCRightClickEvent) {
        BuildingManager.processNpcClick(event.clicker, event.getNPC().id)
    }
}
