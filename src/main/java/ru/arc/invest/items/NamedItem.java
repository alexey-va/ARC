package ru.arc.invest.items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.ToString;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import ru.arc.ARC;

@AllArgsConstructor
@ToString
public class NamedItem extends GenericItem {

    private static final Map<String, NamedItem> namedItemMap = new HashMap<>();

    private ItemStack item;
    private String name;

    public static NamedItem find(String name) {
        return namedItemMap.get(name.toLowerCase());
    }

    public static NamedItem deserialize(Map<String, Object> map, String id) {
        ItemStack stack = ItemStack.deserialize(map);
        return new NamedItem(stack, id);
    }

    public static void load() {
        namedItemMap.clear();
        Path path = Paths.get(ARC.getInstance().getDataFolder().toString(), "investing", "items");
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (var stream = Files.walk(path, 3)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                    .map(Path::toFile)
                    .map(YamlConfiguration::loadConfiguration)
                    .forEach(NamedItem::readFile);
        } catch (Exception e) {
            ARC.getInstance().getLogger().warning("Error loading named items: " + e.getMessage());
        }

        System.out.println("Loaded named items: " + namedItemMap);
    }

    private static void readFile(YamlConfiguration configuration) {
        for (String key : configuration.getKeys(false)) {
            Object val = configuration.get(key);
            if (!(val instanceof Map<?, ?> rawMap)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) rawMap;
            namedItemMap.put(key.toLowerCase(), NamedItem.deserialize(typedMap, key));
        }
    }

    @Override
    public ItemStack stack() {
        return item.clone();
    }

    @Override
    public boolean fits(ItemStack stack) {
        return item.equals(stack.asOne());
    }
}
