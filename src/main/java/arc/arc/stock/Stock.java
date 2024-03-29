package arc.arc.stock;

import arc.arc.board.ItemIcon;
import arc.arc.configs.StockConfig;
import arc.arc.network.repos.RepoData;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

@NoArgsConstructor
@Setter
@Getter
@ToString
@AllArgsConstructor
public class Stock extends RepoData<Stock> {

    String symbol;
    double price;
    double dividend;
    long lastUpdated;
    String display;
    List<String> lore;
    ItemIcon icon;
    long lastTimeDividend;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    int maxLeverage = 10000;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    Type type = Type.STOCK;

/*    public static Stock deserialize(Map<String, Object> map) {
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
                ((Number) map.getOrDefault("lastTimeDividend", 0)).longValue(),
                ((Number) map.getOrDefault("maxLeverage", 10000)).intValue(),
                Type.valueOf(((String) map.getOrDefault("type", "STOCK")).toUpperCase())
        );
    }


    @Override
    public @NotNull Map<String, Object> serialize() {
        try {
            return Map.ofEntries(
                    Map.entry("symbol", symbol),
                    Map.entry("price", price),
                    Map.entry("dividend", dividend),
                    Map.entry("lastUpdated", lastUpdated),
                    Map.entry("display", display),
                    Map.entry("lore", lore),
                    Map.entry("icon", new ObjectMapper().writeValueAsString(icon)),
                    Map.entry("lastTimeDividend", lastTimeDividend),
                    Map.entry("maxLeverage", maxLeverage),
                    Map.entry("type", type.name())
            );
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }*/

    public TagResolver tagResolver() {
        int hours = (int) Duration.between(Instant.ofEpochMilli(lastTimeDividend), Instant.now()).toHours();
        int dividendPeriodHours = (int) (StockConfig.dividendPeriod / 60L / 60L);
        int hoursTill = dividendPeriodHours - hours;
        double low = HistoryManager.low(symbol);
        double high = HistoryManager.high(symbol);
        double volatility = (high-low)/price;
        String volatilityString;
        if(volatility < 0.02) volatilityString = "<dark_green>Низкие";
        else if(volatility < 0.04) volatilityString = "<green>Небольшие";
        else if(volatility < 0.06) volatilityString = "<yellow>Значительные";
        else if(volatility < 0.08) volatilityString = "<red>Высокие";
        else volatilityString = "<dark_red>Импульсивные";
        return TagResolver.builder()
                .resolver(TagResolver.resolver("stock_price", Tag.inserting(
                        mm(formatAmount(price, 5), true)
                )))
                .resolver(TagResolver.resolver("max_leverage", Tag.inserting(
                        mm(formatAmount(maxLeverage), true)
                )))
                .resolver(TagResolver.resolver("lowest_recent_price", Tag.inserting(
                        mm(low == 0.0 ? "<red>Нет" : formatAmount(low), true)
                )))
                .resolver(TagResolver.resolver("highest_recent_price", Tag.inserting(
                        mm(high == 0.0 ? "<red>Нет" : formatAmount(high), true)
                )))
                .resolver(TagResolver.resolver("volatility", Tag.inserting(
                        mm(volatilityString, true)
                )))
                .resolver(TagResolver.resolver("hours_since_dividend", Tag.inserting(
                        mm(hours + "", true)
                )))
                .resolver(TagResolver.resolver("hours_till_dividend", Tag.inserting(
                        mm(Math.max(0, hoursTill) + "", true)
                )))
                .resolver(TagResolver.resolver("dividends_period_hours", Tag.inserting(
                        mm(dividendPeriodHours + "", true)
                )))
                .resolver(TagResolver.resolver("stock_dividend", Tag.inserting(
                        mm(formatAmount(dividend), true)
                ))).build();
    }

    @Override
    public String id() {
        return symbol;
    }

    @Override
    public boolean isRemove() {
        return false;
    }

    @Override
    public void merge(Stock other) {
        this.price = other.price;
        this.dividend = other.dividend;
        this.lastUpdated = other.lastUpdated;
        this.display = other.display;
        this.lore = other.lore;
        this.icon = other.icon;
        this.lastTimeDividend = other.lastTimeDividend;
        this.maxLeverage = other.maxLeverage;
        this.type = other.type;
    }

    public enum Type {
        STOCK, CURRENCY, CRYPTO, COMMODITY
    }
}
