package ru.arc.hooks.slimefun

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.config.ConfigManager

object BackpackBlockListener : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onRightClick(event: PlayerRightClickEvent) {
        if (!isBackpackDisabled()) return
        val item = event.item
        if (item.type == Material.AIR) return
        val sfItem = SlimefunItem.getByItem(item) ?: return
        if (sfItem.id.contains("BACKPACK")) event.cancel()
    }

    /** Disabled on main-server (classic/spawn hub); enabled on survival and other nodes. */
    private fun isBackpackDisabled(): Boolean {
        val misc = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
        return misc.bool("redis.main-server", false)
    }
}
