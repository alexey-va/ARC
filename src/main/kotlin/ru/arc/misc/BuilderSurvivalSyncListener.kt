package ru.arc.misc

import net.william278.husksync.event.BukkitSyncCompleteEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/** Keeps the optional HuskSync API separate from the ordinary join listener. */
internal class BuilderSurvivalSyncListener(private val checkLater: (UUID) -> Unit) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: BukkitSyncCompleteEvent) {
        checkLater(event.user.uuid)
    }
}
