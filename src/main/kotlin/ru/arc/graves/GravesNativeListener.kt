package ru.arc.graves

import dev.cwhead.GravesX.event.GravePreTeleportEvent
import dev.cwhead.GravesX.event.GravePostTeleportEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.util.Logging.warn

/** Covers every GravesX native teleport entry, including old GUI buttons and namespaced commands. */
internal class GravesNativeListener(private val runtime: GravesRuntime) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun before(event: GravePreTeleportEvent) {
        // GravesX emits the modern and deprecated event; the latter must not consume admission twice.
        if (event.javaClass != GravePreTeleportEvent::class.java) return
        val player = event.entity as? Player ?: return
        if (!runtime.payments.admitNative(player, event.grave.uuid)) {
            event.isCancelled = true
            runtime.menu.open(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun charge(event: GravePostTeleportEvent) {
        val player = event.entity as? Player ?: return
        if (event.isCancelled || event.isAddon) return
        try {
            if (!runtime.payments.charge(player, event.grave.uuid, runtime.price(player), runtime.validLocal(player, event.grave.uuid))) {
                event.isCancelled = true
            }
        } catch (failure: Throwable) {
            event.isCancelled = true
            warn("Grave payment post-event failed player={} grave={}", player.uniqueId, event.grave.uuid, failure)
            runtime.feedback(player, "payment-unavailable")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun completed(event: GravePostTeleportEvent) {
        val player = event.entity as? Player ?: return
        runtime.payments.complete(player, event.grave.uuid, event.isCancelled || event.isAddon)
    }
}
