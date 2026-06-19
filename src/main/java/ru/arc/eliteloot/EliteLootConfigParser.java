package ru.arc.eliteloot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Color;
import org.bukkit.Material;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.warn;

public class EliteLootConfigParser {

    private static final Config config = ConfigManager.ofModule(ARC.getInstance().getDataFolder().toPath(), "elite" +
            "-loot.yml");

    public Map<LootType, DecorPool> load() {
        Map<LootType, DecorPool> res = new HashMap<>();
        debug("Loading elite loot config for {} loot types", LootType.values().length);
        for (LootType lootType : LootType.values()) {
            DecorPool pool = parseDecorPool(lootType);
            res.put(lootType, pool);
            debug("Loaded loot type {} with {} items", lootType, pool.getDecorItems().size());
        }
        applyCrossbowFallback(res);
        return res;
    }

    private void applyCrossbowFallback(Map<LootType, DecorPool> pools) {
        DecorPool crossbowPool = pools.get(LootType.CROSSBOW);
        if (crossbowPool == null || !crossbowPool.getDecorItems().isEmpty()) {
            return;
        }
        DecorPool bowPool = pools.get(LootType.BOW);
        if (bowPool == null || bowPool.getDecorItems().isEmpty()) {
            return;
        }
        DecorPool fallback = new DecorPool();
        for (DecorItem bowItem : bowPool.getDecorItems().values()) {
            fallback.add(
                    new DecorItem(
                            Material.CROSSBOW,
                            bowItem.getWeight(),
                            bowItem.getModelId(),
                            bowItem.getColor(),
                            bowItem.getIaNamespace(),
                            bowItem.getIaId()
                    ),
                    bowItem.getWeight()
            );
        }
        pools.put(LootType.CROSSBOW, fallback);
        debug("Crossbow pool empty — reusing {} bow decor entries as crossbow", fallback.getDecorItems().size());
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
            int modelId = decor.containsKey("model-id") ? ((Number) decor.get("model-id")).intValue() : 0;
            double weight = decor.containsKey("weight") ? ((Number) decor.get("weight")).doubleValue() : 1.0;
            Integer red = decor.containsKey("red") ? ((Number) decor.get("red")).intValue() : null;
            Integer green = decor.containsKey("green") ? ((Number) decor.get("green")).intValue() : null;
            Integer blue = decor.containsKey("blue") ? ((Number) decor.get("blue")).intValue() : null;
            String iaNamespace = (String) decor.get("ia-namespace");
            String iaId = (String) decor.get("ia-id");

            if (material == null) {
                warn("Invalid material: {}. Skipping...", decor.get("material"));
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
