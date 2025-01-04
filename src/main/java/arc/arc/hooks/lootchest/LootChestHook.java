package arc.arc.hooks.lootchest;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;

@Slf4j
public class LootChestHook {

    private static LootChestListener lootChestListener;

    public LootChestHook() {
        Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath().resolve("lootchests"), "bosses.yml");
        if (lootChestListener == null) {
            try {
                lootChestListener = new LootChestListener(config);
                Bukkit.getPluginManager().registerEvents(lootChestListener, ARC.plugin);
            } catch (Exception e) {
                log.error("Error while initializing LootChestListener", e);
            }

        }
    }
}
