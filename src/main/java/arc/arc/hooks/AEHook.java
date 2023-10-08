package arc.arc.hooks;

import arc.arc.ARC;
import net.advancedplugins.ae.api.EnchantApplyEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class AEHook implements Listener, ArcModule {

    @EventHandler
    public void aeApply(EnchantApplyEvent event) {
        if (ARC.plugin.sfHook != null && ARC.plugin.sfHook.isSlimefunItem(event.getItem())) event.setCancelled(true);
    }

}
