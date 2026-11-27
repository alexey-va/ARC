package ru.arc.hooks

import com.Zrips.CMI.CMI
import net.william278.husksync.data.DataSnapshot.SaveCause
import net.william278.husksync.data.Identifier
import net.william278.husksync.event.BukkitPreSyncEvent
import net.william278.husksync.user.BukkitUser
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Preserve the destination's charged-flight permission when a different server saved it as off. */
internal class CmiFlightSyncListener(
    private val serverName: String,
    private val hasActiveCharge: (Player) -> Boolean = { player ->
        val charge = CMI.getInstance().playerManager.getUser(player.uniqueId)?.flightCharge
        charge != null && charge.isEnabled && charge.charge > 0
    },
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPreSync(event: BukkitPreSyncEvent) {
        if (event.saveCause != SaveCause.DISCONNECT || event.data.serverName == serverName) return
        val user = event.user as? BukkitUser ?: return
        if (!user.isLocked) return
        val player = user.player
        if (player.gameMode != GameMode.SURVIVAL || !player.allowFlight ||
            !player.hasPermission("cmi.command.flyc") || !hasActiveCharge(player)
        ) return

        event.editData { snapshot ->
            val mode = snapshot.gameMode.orElse(null)?.gameMode
            val flight = snapshot.flightStatus.orElse(null)
            if ((mode == null || mode == GameMode.SURVIVAL.name) && flight?.isAllowFlight == false) {
                snapshot.data.remove(Identifier.FLIGHT_STATUS)
            }
        }
    }
}
