package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.util.TextUtil;
import net.advancedplugins.ae.api.EnchantApplyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class AEHook implements Listener, ArcModule {

    @EventHandler
    public void aeApply(EnchantApplyEvent event) {
        if (ARC.plugin.sfHook != null && ARC.plugin.sfHook.isSlimefunItem(event.getItem())){
            event.setCancelled(true);
            event.getPlayer().sendMessage(TextUtil.strip(
                    Component.text("Это хачарование нельзя наложить на предмет Slimefun", NamedTextColor.RED)
            ));
        }
    }

}