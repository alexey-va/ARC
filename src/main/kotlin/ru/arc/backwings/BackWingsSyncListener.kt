package ru.arc.backwings

import net.william278.husksync.event.BukkitSyncCompleteEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/** Keep the optional HuskSync API out of the ordinary listener's method descriptors. */
internal class BackWingsSyncListener(private val ready: (UUID) -> Unit) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: BukkitSyncCompleteEvent) = ready(event.user.uuid)
}
