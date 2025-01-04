package arc.arc.common.treasure.impl;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.common.treasure.GiveFlags;
import arc.arc.common.treasure.Treasure;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class AEEnchant extends Treasure {

    int weight;
    Map<String, Object> attributes;
    private static Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasures.yml");

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {

    }

    @Override
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("attributes", attributes);
        map.put("weight", weight);
        map.put("type", "ae_enchant");
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {

    }
}
