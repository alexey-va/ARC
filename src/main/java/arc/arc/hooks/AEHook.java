package arc.arc.hooks;

import arc.arc.ARC;
import net.advancedplugins.ae.api.EffectActivationEvent;
import net.advancedplugins.ae.api.EnchantActivateEvent;
import net.advancedplugins.ae.api.EnchantApplyEvent;
import net.advancedplugins.ae.api.EnchantDestroyItemEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class AEHook implements Listener {

    @EventHandler
    public void aeApply(EnchantApplyEvent event) {
        if (ARC.hookRegistry.sfHook != null && ARC.hookRegistry.sfHook.isSlimefunItem(event.getItem())) event.setCancelled(true);
    }

}
