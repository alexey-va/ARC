package ru.arc.graves

import net.william278.husksync.event.BukkitSyncCompleteEvent
import net.william278.husksync.event.BukkitDataSaveEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/** Isolated optional API; a successful inventory sync must precede a transferred grave visit. */
internal class GravesSyncListener(private val ready: (UUID) -> Unit, private val persistence: HuskSyncGravePersistence) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: BukkitSyncCompleteEvent) { ready(event.user.uuid) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun saving(event: BukkitDataSaveEvent) { persistence.beforeSave(event) }
}
