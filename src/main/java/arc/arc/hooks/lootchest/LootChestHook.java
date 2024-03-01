package arc.arc.hooks.lootchest;

import arc.arc.ARC;
import org.bukkit.Bukkit;

public class LootChestHook {

    private static LootChestListener lootChestListener;

    public LootChestHook() {
        lootChestListener = new LootChestListener();
        Bukkit.getPluginManager().registerEvents(lootChestListener, ARC.plugin);
    }
}
