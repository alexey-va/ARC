package arc.arc.stock;

import arc.arc.configs.StockConfig;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.repos.RepoData;
import arc.arc.util.TextUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static arc.arc.util.TextUtil.mm;

@AllArgsConstructor
@NoArgsConstructor
@ToString
@Setter
@Getter
public class StockPlayer extends RepoData<StockPlayer> {

    String playerName;
    UUID playerUuid;
    Map<String, List<Position>> positionMap = new HashMap<>();
    private double balance = 0;
    boolean autoTake = true;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    double totalGains = 0;
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    double receivedDividend = 0;

    public StockPlayer(String name, UUID uuid) {
        this.playerUuid = uuid;
        this.playerName = name;
        setDirty(true);
    }

    @JsonIgnore
    public synchronized void addToBalance(double add, boolean fromPosition) {
        balance += add;
        if (fromPosition) totalGains += add;
        setDirty(true);
    }

    @JsonIgnore
    public synchronized double totalBalance() {
        double fromPositions = positionMap.values().stream()
                .flatMap(list -> list.stream().map(Position::totalValue))
                .reduce(0.0, Double::sum);
        //System.out.println("From positions: "+fromPositions);
        return balance + fromPositions;
    }

    @JsonIgnore
    public synchronized double giveDividend(String symbol) {
        if (!positionMap.containsKey(symbol)) return 0;
        System.out.println("Giving divedend to "+playerName);
        Stock stock = StockMarket.stock(symbol);
        if (stock.dividend < 0.00001) return 0;

        setDirty(true);

        double gave = 0;
        for (Position position : positionMap.get(symbol)) {
            double dividend = stock.dividend * position.amount;
            if (dividend == 0) continue;
            balance += dividend;
            receivedDividend += dividend;
            gave+=dividend;
            position.setReceivedDividend(position.getReceivedDividend()+dividend);
        }
        return gave;
    }

    @JsonIgnore
    public synchronized Optional<Position> find(String symbol, UUID uuid) {
        if (!positionMap.containsKey(symbol)) return Optional.empty();
        return positionMap.get(symbol).stream().filter(p -> p.positionUuid.equals(uuid)).findAny();
    }

    @JsonIgnore
    public boolean isBelowMaxStockAmount(){
        int currentAmount = positions().size();
        if(currentAmount < StockConfig.defaultStockMaxAmount) return true;
        var entry = StockConfig.permissionMap.ceilingEntry(currentAmount+1);
        if(entry == null) return false;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        if(!offlinePlayer.isOnline() && HookRegistry.luckPermsHook == null) return false;
        return HookRegistry.luckPermsHook.hasPermission(offlinePlayer, entry.getValue());
    }

    @JsonIgnore
    public int maxStockAmount(){
        int max = -1;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        if(!offlinePlayer.isOnline() && HookRegistry.luckPermsHook == null) return -1;
        for(var entry : StockConfig.permissionMap.entrySet()){
            if(HookRegistry.luckPermsHook.hasPermission(offlinePlayer, entry.getValue())
                    && entry.getKey() > max) max = entry.getKey();
        }
        return max == -1 ? StockConfig.defaultStockMaxAmount : max;
    }

    @JsonIgnore
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
                .resolver(TagResolver.resolver("position_amount", Tag.inserting(
                        mm(positions().size()+"", true)
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

    @JsonIgnore
    public synchronized Optional<Position> remove(String symbol, UUID uuid) {
        if (!positionMap.containsKey(symbol)) {
            //System.out.println("No key for symbol "+symbol);
            return Optional.empty();
        }
        for (Position position : positionMap.get(symbol)) {
            if (position.positionUuid.equals(uuid)) {
                setDirty(true);
                positionMap.get(symbol).remove(position);
                return Optional.of(position);
            }
        }
        return Optional.empty();
    }

    @JsonIgnore
    public synchronized void addPosition(Position position) {
        if (position == null) {
            System.out.println("Position is null!");
            return;
        }
        setDirty(true);
        positionMap.putIfAbsent(position.symbol, new CopyOnWriteArrayList<>());
        positionMap.get(position.symbol).add(position);
    }

    public void setAutoTake(boolean autoTake) {
        if (autoTake == this.autoTake) return;
        setDirty(true);
        this.autoTake = autoTake;
    }


    @JsonIgnore
    public synchronized List<Position> positions(String symbol) {
        return positionMap.get(symbol);
    }

    @JsonIgnore
    public synchronized List<Position> positions() {
        return positionMap.values().stream().flatMap(Collection::stream).toList();
    }

    @JsonIgnore
    public synchronized double totalGains() {
        return positionMap.values().stream()
                .flatMap(list -> list.stream().map(Position::gains))
                .reduce(0.0, Double::sum);
    }
    @JsonIgnore
    public Player player() {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        if(offlinePlayer instanceof Player p) return p;
        return null;
    }

    @Override
    public String id() {
        return playerUuid.toString();
    }

    @Override
    public boolean isRemove() {
        return false;
    }

    @Override
    public synchronized void merge(StockPlayer other) {
        if (other == null) return;
        if (other.balance != 0) balance = other.balance;
        if (other.autoTake != autoTake) autoTake = other.autoTake;
        if (other.totalGains != 0) totalGains = other.totalGains;
        if (other.receivedDividend != 0) receivedDividend = other.receivedDividend;
        if (other.positionMap != null){
            positionMap.clear();
            positionMap.putAll(other.positionMap);
        }
    }
}
