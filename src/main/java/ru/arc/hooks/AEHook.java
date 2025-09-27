package ru.arc.hooks;

import net.advancedplugins.ae.api.AEAPI;
import net.advancedplugins.ae.api.EnchantApplyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public class AEHook implements Listener {

    @EventHandler
    public void aeApply(EnchantApplyEvent event) {
        if (HookRegistry.sfHook != null && HookRegistry.sfHook.isSlimefunItem(event.getItem())) event.setCancelled(true);
    }
    
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEnchantTable(EnchantItemEvent event) {
        ItemStack item = event.getItem();
        boolean slimefunItem = HookRegistry.sfHook.isSlimefunItem(item);
        ItemStack result = event.getItem();
        if (slimefunItem) {
            HashMap<String, Integer> enchantmentsOnItem = AEAPI.getEnchantmentsOnItem(item);
            for (String enchantment : enchantmentsOnItem.keySet()) {
                result = AEAPI.removeEnchantment(result, enchantment);
            }
        }
        if (result != item) {
            event.setItem(result);
        }
    }

}
