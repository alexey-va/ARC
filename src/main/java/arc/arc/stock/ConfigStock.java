package arc.arc.stock;

import arc.arc.board.ItemIcon;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.bukkit.Material;

import java.util.*;

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
        } else if(o instanceof Map iconMap){
            if(iconMap != null){
                String uuidString = (String) iconMap.getOrDefault("headUuid", null);
                UUID uuid = uuidString == null || uuidString.length() < 36  ? null : UUID.fromString(uuidString);
                icon = new ItemIcon(
                        Material.valueOf(((String) iconMap.getOrDefault("material", "PAPER")).toUpperCase()),
                        uuid,
                        ((Number) iconMap.getOrDefault("data", 0)).intValue()
                );
            }
        }




        Object l = map.getOrDefault("lore", List.of("lore"));
        List<String> lore = new ArrayList<>();
        if (l instanceof String s) lore.add(s);
        else if (l instanceof Collection<?> list) lore.addAll((Collection<? extends String>) list);

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
