package arc.arc.hooks.elitemobs;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;

public class EMListener implements Listener {

    final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "elitemobs.yml");
    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        List<String> noExpWorlds = config.stringList("no-explosion-worlds");
        String name = event.getExplosionSourceLocation().getWorld().getName();
        if (noExpWorlds.contains(name)) event.setCancelled(true);
    }

}
