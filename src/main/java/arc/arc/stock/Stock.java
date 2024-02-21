package arc.arc.stock;

import arc.arc.board.ItemIcon;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

@NoArgsConstructor
@Setter @Getter
@ToString
@AllArgsConstructor
public class Stock implements ConfigurationSerializable {

    String symbol;
    double price;
    double dividend;
    long lastUpdated;
    String display;
    List<String> lore;
    ItemIcon icon;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    boolean isStock = false;
    long lastTimeDividend;

    public static Stock deserialize(Map<String, Object> map) {
        String jsonIcon = (String) map.get("icon");
        ItemIcon icon = ItemIcon.of(Material.PAPER, 0);
        try {
            icon = new ObjectMapper().readValue(jsonIcon, ItemIcon.class);
        } catch (Exception e) {
            //e.printStackTrace();
        }

        Object o = map.getOrDefault("lore", List.of("lore"));
        List<String> result = new ArrayList<>();
        if (o instanceof String s) result.add(s);
        else if (o instanceof Collection<?> list) result.addAll((Collection<? extends String>) list);

        return new Stock(
                (String) map.get("symbol"),
                ((Number) map.getOrDefault("price", 1000_000_000.0)).doubleValue(),
                ((Number) map.getOrDefault("dividend", 0.0)).doubleValue(),
                ((Number) map.getOrDefault("lastUpdated", 0)).longValue(),
                (String) map.getOrDefault("display", "display"),
                result,
                icon,
                (Boolean) map.getOrDefault("isStock", false),
                ((Number) map.getOrDefault("lastTimeDividend", 0)).longValue()
        );
    }


    @Override
    public @NotNull Map<String, Object> serialize() {
        try {
            return Map.of(
                    "symbol", symbol,
                    "price", price,
                    "dividend", dividend,
                    "lastUpdated", lastUpdated,
                    "display", display,
                    "lore", lore,
                    "icon", new ObjectMapper().writeValueAsString(icon),
                    "isStock", isStock,
                    "lasTimeDividend", lastTimeDividend
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public TagResolver tagResolver() {
        return TagResolver.builder()
                .resolver(TagResolver.resolver("price", Tag.inserting(
                        mm(formatAmount(price), true)
                )))
                .resolver(TagResolver.resolver("dividend", Tag.inserting(
                        mm(formatAmount(dividend), true)
                ))).build();
    }
}
