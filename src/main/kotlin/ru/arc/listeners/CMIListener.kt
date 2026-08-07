package ru.arc.listeners

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.Portals.CMIPortal
import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.ARC
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.PortalData.ActionType.TELEPORT
import ru.arc.config.ConfigManager
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.util.Logging.info

class CMIListener : Listener {

    private val commandConfig = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!commandConfig.bool("portal.cmi-command-only-on-join", false)) return
        val player = event.player
        if (player.hasPermission("arc.bypass-portal")) return
        val initialPortalName = commandOnlyPortalAt(player.location)?.name ?: return

        delayed(1.ticks) {
            if (!player.isOnline || player.hasPermission("arc.bypass-portal")) return@delayed
            val portal = commandOnlyPortalAt(player.location) ?: return@delayed
            if (portal.name != initialPortalName) return@delayed
            val triggered = portal.teleport(player)
            info(
                "CMI command-only join portal {} for player {} triggered={}",
                portal.name,
                player.name,
                triggered,
            )
        }
    }

    @EventHandler
    fun onCMITp(event: CMIAsyncPlayerTeleportEvent) {
        if (event.sender.hasPermission("arc.bypass-portal")) return
        if (event.type == null || event.to == null || event.player == null) return
        val types = commandConfig.stringList("portal.cmi-tp-types").map { it.lowercase() }.toSet()
        if (!types.contains(event.type.toString().lowercase())) return
        event.isCancelled = true
        Portal(event.player.uniqueId, PortalData(TELEPORT, null, event.to, null))
    }

    private fun commandOnlyPortalAt(location: org.bukkit.Location): CMIPortal? {
        val portal = CMI.getInstance().portalManager.getByLoc(location) ?: return null
        return portal.takeIf {
            isCommandOnlyPortal(
                enabled = it.isEnabled,
                performCommandsWithoutTp = it.performCommandsWithoutTp,
                hasTeleportLocation = it.tpLoc != null,
                hasBungeeDestination = it.bungeeServer != null,
            )
        }
    }
}

internal fun isCommandOnlyPortal(
    enabled: Boolean,
    performCommandsWithoutTp: Boolean,
    hasTeleportLocation: Boolean,
    hasBungeeDestination: Boolean,
): Boolean =
    enabled &&
        performCommandsWithoutTp &&
        !hasTeleportLocation &&
        !hasBungeeDestination
