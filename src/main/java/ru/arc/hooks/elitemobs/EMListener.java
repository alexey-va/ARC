package ru.arc.hooks.elitemobs;

import java.util.List;

import com.magmaguy.elitemobs.api.EliteExplosionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

public class EMListener implements Listener {

    final Config config = ConfigManager.of(ARC.getInstance().getDataPath(), "elitemobs.yml");
    @EventHandler
    public void emExplosion(EliteExplosionEvent event) {
        List<String> noExpWorlds = config.stringList("no-explosion-worlds");
        String name = event.getExplosionSourceLocation().getWorld().getName();
        if (noExpWorlds.contains(name)) event.setCancelled(true);
    }

}
