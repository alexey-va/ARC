package ru.arc.hooks;

import net.advancedplugins.ae.api.EnchantApplyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.arc.ARC;

public class AEHook implements Listener {

    @EventHandler
    public void aeApply(EnchantApplyEvent event) {
        if (ARC.hookRegistry.sfHook != null && ARC.hookRegistry.sfHook.isSlimefunItem(event.getItem())) event.setCancelled(true);
    }

}
