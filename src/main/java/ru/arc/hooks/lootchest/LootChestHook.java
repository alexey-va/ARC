package ru.arc.hooks.lootchest;

import org.bukkit.Bukkit;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import static ru.arc.util.Logging.error;

public class LootChestHook {

    private static LootChestListener lootChestListener;

    public LootChestHook() {
        Config config = ConfigManager.of(ARC.getInstance().getDataFolder().toPath().resolve("lootchests"), "bosses" +
                ".yml");
        if (lootChestListener == null) {
            try {
                lootChestListener = new LootChestListener(config);
                Bukkit.getPluginManager().registerEvents(lootChestListener, ARC.getInstance());
            } catch (Exception e) {
                error("Error while initializing LootChestListener", e);
            }

        }
    }
}
