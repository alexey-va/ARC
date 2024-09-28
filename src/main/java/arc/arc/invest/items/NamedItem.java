package arc.arc.invest.items;

import arc.arc.ARC;
import lombok.AllArgsConstructor;
import lombok.ToString;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@ToString
public class NamedItem extends GenericItem {

    private static Map<String, NamedItem> namedItemMap = new HashMap<>();

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
        Path path = Paths.get(ARC.plugin.getDataFolder().toString(), "investing", "items");
        if(!Files.exists(path)){
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
            e.printStackTrace();
        }

        System.out.println("Loaded named items: " + namedItemMap);
    }

    private static void readFile(YamlConfiguration configuration) {
        for (String key : configuration.getKeys(false)) {
            namedItemMap.put(key.toLowerCase(), NamedItem.deserialize((Map<String, Object>) configuration.get(key), key));
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
