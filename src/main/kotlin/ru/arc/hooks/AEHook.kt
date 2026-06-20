package ru.arc.hooks

import net.advancedplugins.ae.api.AEAPI
import net.advancedplugins.ae.api.EnchantApplyEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent

class AEHook : Listener {

    @EventHandler
    fun aeApply(event: EnchantApplyEvent) {
        if (HookRegistry.sfHook?.isSlimefunItem(event.item) == true) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onEnchantTable(event: EnchantItemEvent) {
        val item = event.item
        val sfHook = HookRegistry.sfHook ?: return
        if (!sfHook.isSlimefunItem(item)) return
        var result = item
        val enchantments = AEAPI.getEnchantmentsOnItem(item)
        for (enchantment in enchantments.keys) {
            result = AEAPI.removeEnchantment(result, enchantment)
        }
        if (result !== item) event.item = result
    }
}
