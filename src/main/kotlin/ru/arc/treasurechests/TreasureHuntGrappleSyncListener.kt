package ru.arc.treasurechests

import net.william278.husksync.event.BukkitSyncCompleteEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/**
 * Kept in a separate class so servers without HuskSync do not resolve its
 * optional API while loading the grapple controller.
 */
internal class TreasureHuntGrappleSyncListener(
    private val reconcilePlayer: (UUID) -> Unit,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun handleSyncComplete(event: BukkitSyncCompleteEvent) {
        reconcilePlayer(event.user.uuid)
    }
}
