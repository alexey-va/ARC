package ru.arc.listeners

import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.PortalData.ActionType.TELEPORT
import ru.arc.configs.ConfigManager

class CMIListener : Listener {

    private val commandConfig = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler
    fun onCMITp(event: CMIAsyncPlayerTeleportEvent) {
        if (event.sender.hasPermission("arc.bypass-portal")) return
        if (event.type == null || event.to == null || event.player == null) return
        val types = commandConfig.stringList("portal.cmi-tp-types").map { it.lowercase() }.toSet()
        if (!types.contains(event.type.toString().lowercase())) return
        event.isCancelled = true
        Portal(event.player.uniqueId, PortalData(TELEPORT, null, event.to, null))
    }
}
