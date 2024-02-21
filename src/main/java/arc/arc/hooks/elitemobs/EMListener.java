package arc.arc.hooks.elitemobs;

import arc.arc.configs.Config;
import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class EMListener implements Listener {

    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        if (Config.noExpWorlds.isEmpty()) return;
        if (Config.noExpWorlds.contains(event.getExplosionSourceLocation().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

}
