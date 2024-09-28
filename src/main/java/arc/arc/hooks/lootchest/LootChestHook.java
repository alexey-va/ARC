package arc.arc.hooks.lootchest;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import org.bukkit.Bukkit;

public class LootChestHook {

    private static LootChestListener lootChestListener;

    public LootChestHook() {
        Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath().resolve("lootchests"), "bosses.yml");
        if (lootChestListener == null) {
            lootChestListener = new LootChestListener(config);
            Bukkit.getPluginManager().registerEvents(lootChestListener, ARC.plugin);
        }
    }
}
