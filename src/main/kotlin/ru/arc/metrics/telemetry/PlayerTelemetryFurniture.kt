package ru.arc.metrics.telemetry

import dev.lone.itemsadder.api.Events.FurnitureBreakEvent
import dev.lone.itemsadder.api.Events.FurnitureInteractEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceEvent
import dev.lone.itemsadder.api.Events.FurniturePlaceSuccessEvent
import org.bukkit.event.EventPriority
import ru.arc.core.EventScope

/** Loaded only when the native ItemsAdder API is available. Observations never change the event. */
internal object PlayerTelemetryFurniture {
    fun register(scope: EventScope) {
        scope.on<FurniturePlaceEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
            PlayerTelemetryModule.action(it.player, "furniture.place_attempt", it.namespacedID,
                mapOf("cancelled" to it.isCancelled.toString()))
        }
        scope.on<FurniturePlaceSuccessEvent>(EventPriority.MONITOR) { event ->
            val player = event.player
            if (player != null) PlayerTelemetryModule.action(player, "furniture.placed", event.namespacedID)
        }
        scope.on<FurnitureBreakEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
            PlayerTelemetryModule.action(it.player, "furniture.break", it.namespacedID,
                mapOf("cancelled" to it.isCancelled.toString()))
        }
        scope.on<FurnitureInteractEvent>(EventPriority.MONITOR, ignoreCancelled = false) {
            PlayerTelemetryModule.action(it.player, "furniture.interact", it.namespacedID,
                mapOf("cancelled" to it.isCancelled.toString()))
        }
    }
}
