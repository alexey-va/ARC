package arc.arc.stock;

import arc.arc.util.TextUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static arc.arc.util.TextUtil.mm;

@AllArgsConstructor
@NoArgsConstructor
@ToString
@Setter
@Getter
public class StockPlayer {

    String playerName;
    UUID playerUuid;
    Map<String, List<Position>> positionMap = new HashMap<>();
    private double balance = 0;
    boolean autoTake = false;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    double totalGains = 0;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    double receivedDividend = 0;

    @JsonIgnore
    @Setter
    @Getter
    boolean dirty = false;

    public StockPlayer(String name, UUID uuid) {
        this.playerUuid = uuid;
        this.playerName = name;
        dirty = true;
    }

    public void addToBalance(double add, boolean fromPosition) {
        balance += add;
        if (fromPosition) totalGains += add;
        dirty = true;
    }

    public double totalBalance() {
        double fromPositions = positionMap.values().stream()
                .flatMap(list -> list.stream().map(Position::gains))
                .reduce(0.0, Double::sum);
        //System.out.println("From positions: "+fromPositions);
        return balance + fromPositions;
    }

    public void giveDividend(String symbol) {
        if (!positionMap.containsKey(symbol)) return;
        Stock stock = StockMarket.stock(symbol);
        if (!stock.isStock()) return;
        dirty=true;
        for (Position position : positionMap.get(symbol)) {
            double dividend = stock.dividend * position.amount;
            if (dividend == 0) continue;
            balance += dividend;
            receivedDividend += dividend;
            position.setReceivedDividend(position.getReceivedDividend()+dividend);
        }
    }

    public Optional<Position> find(String symbol, UUID uuid) {
        if (!positionMap.containsKey(symbol)) return Optional.empty();
        return positionMap.get(symbol).stream().filter(p -> p.positionUuid.equals(uuid)).findAny();
    }

    public Position.BankruptResponse checkPosition(String symbol, double currentPrice, UUID uuid) {
        Optional<Position> position = find(symbol, uuid);
        if (position.isEmpty()) {
            System.out.println("Could not find position with symbol " + symbol + " " + uuid);
            return null;
        }

        Position pos = position.get();
        return pos.bankrupt(currentPrice, balance);
    }

    public TagResolver tagResolver() {
        double bal = this.getBalance();
        double total = this.totalBalance();
        return TagResolver.builder()
                .resolver(TagResolver.resolver("balance", Tag.inserting(
                        mm(TextUtil.formatAmount(bal), true)
                )))
                .resolver(TagResolver.resolver("name", Tag.inserting(
                        mm(playerName, true)
                )))
                .resolver(TagResolver.resolver("uuid", Tag.inserting(
                        mm(playerUuid.toString().split("-")[0], true)
                )))
                .resolver(TagResolver.resolver("auto_take", Tag.inserting(
                        mm(autoTake ? "<green>Да" : "<red>Нет", true)
                )))
                .resolver(TagResolver.resolver("received_dividends", Tag.inserting(
                        mm(TextUtil.formatAmount(receivedDividend), true)
                )))
                .resolver(TagResolver.resolver("total_balance", Tag.inserting(
                        mm(TextUtil.formatAmount(total), true)
                )))
                .resolver(TagResolver.resolver("gains", Tag.inserting(
                        mm(TextUtil.formatAmount(total-bal), true)
                )))
                .resolver(TagResolver.resolver("total_gains", Tag.inserting(
                        mm(TextUtil.formatAmount(getTotalGains()), true)
                )))
                .resolver(TagResolver.resolver("position_count", Tag.inserting(
                        mm(positions().size() + "", true)
                )))
                .build();
    }

    public Optional<Position> remove(String symbol, UUID uuid) {
        if (!positionMap.containsKey(symbol)) {
            //System.out.println("No key for symbol "+symbol);
            return Optional.empty();
        }
        for (Position position : positionMap.get(symbol)) {
            if (position.positionUuid.equals(uuid)) {
                dirty = true;
                positionMap.get(symbol).remove(position);
                return Optional.of(position);
            }
        }
        return Optional.empty();
    }

    public void addPosition(Position position) {
        if (position == null) {
            System.out.println("Position is null!");
            return;
        }
        dirty = true;
        positionMap.putIfAbsent(position.symbol, new CopyOnWriteArrayList<>());
        positionMap.get(position.symbol).add(position);
    }

    public void setAutoTake(boolean autoTake) {
        if (autoTake == this.autoTake) return;
        dirty = true;
        this.autoTake = autoTake;
    }


    public List<Position> positions(String symbol) {
        return positionMap.get(symbol);
    }

    public List<Position> positions() {
        return positionMap.values().stream().flatMap(Collection::stream).toList();
    }

    public double totalGains() {
        return positionMap.values().stream()
                .flatMap(list -> list.stream().map(Position::gains))
                .reduce(0.0, Double::sum);
    }
}
