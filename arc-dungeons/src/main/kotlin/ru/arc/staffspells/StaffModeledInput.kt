package ru.arc.staffspells

import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.EquipmentSlot

/** Loaded only with FMM; modeled hitboxes emit their own events instead of Paper interactions. */
internal class StaffModeledInput(private val input: StaffSpellInput) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeftClick(event: ModeledEntityLeftClickEvent) = input.leftClickModeledEntity(event.player) {
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onRightClick(event: ModeledEntityRightClickEvent) =
        input.interactEntity(event.player, EquipmentSlot.HAND) { event.isCancelled = true }
}
