package arc.arc.hooks.elitemobs;

import arc.arc.configs.Config;
import arc.arc.configs.MainConfig;
import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;

@RequiredArgsConstructor
public class EMListener implements Listener {

    final Config config;
    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        List<String> noExpWorlds = config.stringList("no-explosion-worlds");
        String name = event.getExplosionSourceLocation().getWorld().getName();
        if (noExpWorlds.contains(name)) event.setCancelled(true);
    }

}
