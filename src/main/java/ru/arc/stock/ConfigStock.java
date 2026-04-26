package ru.arc.stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.bukkit.Material;
import ru.arc.board.ItemIcon;

@Data
@AllArgsConstructor
public class ConfigStock {

    String symbol;
    String display;
    List<String> lore;
    ItemIcon icon;
    int maxLeverage;
    Stock.Type type;

    public Stock toStock(double price, double dividend, long lastUpdated, long lastTimeDividend){
        return new Stock(
                symbol,
                price,
                dividend,
                lastUpdated,
                display,
                lore,
                icon,
                lastTimeDividend,
                maxLeverage,
                type
        );
    }

    public static ConfigStock deserialize(Map<String, Object> map) {

        Object o = map.get("icon");
        ItemIcon icon = ItemIcon.of(Material.PAPER, 0);
        if(o instanceof String str){
            icon = new Gson().fromJson(str, ItemIcon.class);
        } else if (o instanceof Map<?, ?> iconMap) {
            String uuidString = iconMap.get("headUuid") instanceof String s ? s : null;
            UUID uuid = uuidString == null || uuidString.length() < 36 ? null : UUID.fromString(uuidString);
            String materialStr = iconMap.get("material") instanceof String ms ? ms : "PAPER";
            int data = iconMap.get("data") instanceof Number n ? n.intValue() : 0;
            icon = new ItemIcon(Material.valueOf(materialStr.toUpperCase()), uuid, data);
        }




        Object l = map.getOrDefault("lore", List.of("lore"));
        List<String> lore = new ArrayList<>();
        if (l instanceof String s) {
            lore.add(s);
        } else if (l instanceof Collection<?> list) {
            for (Object item : list) {
                if (item instanceof String str) {
                    lore.add(str);
                }
            }
        }

        return new ConfigStock(
                (String) map.get("symbol"),
                (String) map.getOrDefault("display", "display"),
                lore,
                icon,
                ((Number) map.getOrDefault("maxLeverage", 10000)).intValue(),
                Stock.Type.valueOf(((String) map.getOrDefault("type", "STOCK")).toUpperCase())
        );
    }

}
