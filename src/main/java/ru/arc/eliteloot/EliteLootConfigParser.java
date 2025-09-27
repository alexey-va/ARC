package ru.arc.eliteloot;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.arc.util.Logging.info;

@Slf4j
public class EliteLootConfigParser {

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "elite-loot.yml");

    public Map<LootType, DecorPool> load() {
        Map<LootType, DecorPool> res = new HashMap<>();
        for (LootType lootType : LootType.values()) {
            res.put(lootType, parseDecorPool(lootType));
        }
        return res;
    }


    public void addDecor(LootType lootType, DecorItem decorItem) {
        Map<String, Object> serializedDecor = new HashMap<>();
        serializedDecor.put("material", decorItem.getMaterial().name());
        serializedDecor.put("model-id", decorItem.getModelId());
        serializedDecor.put("weight", decorItem.getWeight());
        if (decorItem.getColor() != null) {
            serializedDecor.put("red", decorItem.getColor().getRed());
            serializedDecor.put("green", decorItem.getColor().getGreen());
            serializedDecor.put("blue", decorItem.getColor().getBlue());
        }
        serializedDecor.put("ia-namespace", decorItem.getIaNamespace());
        serializedDecor.put("ia-id", decorItem.getIaId());
        config.addToList("elite-loot." + lootType.name().toLowerCase(), serializedDecor);
    }

    private DecorPool parseDecorPool(LootType lootType) {
        DecorPool pool = new DecorPool();
        List<Map<String, Object>> decors = config.list("elite-loot." + lootType.name().toLowerCase());
        if (decors == null || decors.isEmpty()) return pool;
        for (Map<String, Object> decor : decors) {
            Material material = Material.matchMaterial(decor.get("material").toString().toUpperCase());
            int modelId = (Integer) decor.getOrDefault("model-id", 0);
            double weight = (Double) decor.getOrDefault("weight", 1);
            Integer red = (Integer) decor.get("red");
            Integer green = (Integer) decor.get("green");
            Integer blue = (Integer) decor.get("blue");
            String iaNamespace = (String) decor.get("ia-namespace");
            String iaId = (String) decor.get("ia-id");

            info("Loaded decor - material: {} model: {} weight: {} red: {} green: {} blue: {}", material, modelId, weight, red, green, blue);
            if (material == null) {
                info("Invalid material: {}. Skipping...", decor.get("material"));
                continue;
            }
            Color color = null;
            if (red != null && green != null && blue != null) {
                color = Color.fromRGB(red, green, blue);
            }
            pool.add(new DecorItem(material, weight, modelId, color, iaNamespace, iaId), weight);
        }
        return pool;
    }

}
