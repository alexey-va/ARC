package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.util.TextUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

public class StockPlayerManager {

    private static BukkitTask saveTask;
    private static final Map<UUID, StockPlayer> playerMap = new ConcurrentHashMap<>();
    @Setter
    private static StockPlayerMessager messager;
    static BukkitTask dividendTask;

    public static void startTasks() {
        cancelTasks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveStockPlayers();
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 20L);

        dividendTask = new BukkitRunnable() {
            @Override
            public void run() {
                if(LocalTime.now().getHour() != 0) return;
                StockMarket.stocks().stream()
                        .filter(Stock::isStock)
                        .filter(s ->System.currentTimeMillis() - s.lastTimeDividend >= 23*60*60*1000L)
                        .map(Stock::getSymbol)
                        .forEach(StockPlayerManager::giveDividend);
            }
        }.runTaskTimer(ARC.plugin, 100L, 20L*60);
    }

    public static void updateAllPositionsOf(String symbol) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Stock " + symbol + " is null while trying to update positions!");
            return;
        }

        playerMap.values().stream().filter(sp -> sp.positionMap.containsKey(symbol))
                .forEach(sp -> checkPosition(sp, symbol));

    }

    private static void checkPosition(StockPlayer stockPlayer, String symbol) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Stock " + symbol + " is null while trying to update positions!");
            return;
        }
        List<Position> positionsClone = new ArrayList<>(stockPlayer.positions(symbol));
        for (Position position : positionsClone) {
            Position.BankruptResponse response = position.bankrupt(stock.price, stockPlayer.getBalance());
            if (response.bankrupt()) {
                System.out.println("Bankrupt for "+position);
                if (stockPlayer.isAutoTake()) {
                    boolean addedToBalanceSuccess = addToTradingBalanceFromVault(stockPlayer, response.total());
                    if (addedToBalanceSuccess) continue;
                }
                closePosition(stockPlayer, symbol, position.getPositionUuid(), 2);
            }

            int marginCall = position.marginCall(stock.getPrice());
            if(marginCall != 0){
                System.out.println("Margin call "+marginCall+" for "+position);
                closePosition(stockPlayer, position.getSymbol(), position.getPositionUuid(), 1);
            }
        }
    }

    public static void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
        if(dividendTask != null && !dividendTask.isCancelled()) dividendTask.cancel();
    }

    private static void giveDividend(String symbol){
        Stock stock = StockMarket.stock(symbol);
        if(!stock.isStock()) return;
        for(StockPlayer stockPlayer : playerMap.values()){
            stockPlayer.giveDividend(symbol);
        }
        stock.setLastTimeDividend(System.currentTimeMillis());
    }

    private static StockPlayer createPlayer(String playerName, UUID playerUuid) {
        StockPlayer stockPlayer = new StockPlayer(playerName, playerUuid);
        playerMap.put(playerUuid, stockPlayer);
        saveStockPlayer(stockPlayer);
        return stockPlayer;
    }

    public static StockPlayer getOrCreate(Player player) {
        StockPlayer stockPlayer = playerMap.get(player.getUniqueId());
        if (stockPlayer != null) return stockPlayer;
        return createPlayer(player.getName(), player.getUniqueId());
    }

    public static void buyStock(StockPlayer stockPlayer, Stock stock, double amount, int leverage, double lowerBound, double upperBound) {
        EconomyCheckResponse response = economyCheck(stockPlayer, stock, amount, leverage);
        if (!response.success()) {
            System.out.println(response);
            //TextUtil.noMoneyMessage(player, response.lack);
            return;
        }

        stockPlayer.addToBalance(-response.totalPrice, true);
        Position position = new Position.PositionBuilder()
                .amount(amount)
                .startPrice(stock.price)
                .positionUuid(UUID.randomUUID())
                .symbol(stock.getSymbol())
                .leverage(leverage)
                .upperBoundMargin(upperBound)
                .lowerBoundMargin(lowerBound)
                .commission(response.commission())
                .timestamp(System.currentTimeMillis())
                .type(Position.Type.BOUGHT)
                .build();
        stockPlayer.addPosition(position);
        //player.sendMessage("Successfully added position: " + position);
    }

    public static void shortStock(StockPlayer stockPlayer, Stock stock, double amount, int leverage, double lowerBound, double upperBound) {
        EconomyCheckResponse response = economyCheck(stockPlayer, stock, amount, leverage);
        if (!response.success()) {
            System.out.println(response);
            //TextUtil.noMoneyMessage(player, response.lack);
            return;
        }

        stockPlayer.addToBalance(-response.totalPrice, true);
        Position position = new Position.PositionBuilder()
                .amount(amount)
                .startPrice(stock.price)
                .positionUuid(UUID.randomUUID())
                .symbol(stock.getSymbol())
                .leverage(leverage)
                .upperBoundMargin(upperBound)
                .lowerBoundMargin(lowerBound)
                .commission(response.commission())
                .timestamp(System.currentTimeMillis())
                .type(Position.Type.SHORTED)
                .build();
        stockPlayer.addPosition(position);
        //player.sendMessage("Successfully added position: " + position);
    }

    public static void closePosition(StockPlayer stockPlayer, String symbol, UUID positionUuid, int reason) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Could not find stock with symbol: " + symbol);
            return;
        }
        stockPlayer.remove(symbol, positionUuid).ifPresentOrElse(position -> {
            double gains = position.gains(stock.price);
            stockPlayer.addToBalance(gains+position.startPrice*position.amount, true);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(stockPlayer.playerUuid);
            if(offlinePlayer.isOnline()){
                Player player = (Player) offlinePlayer;
                TagResolver resolver = TagResolver.builder()
                        .resolver(TagResolver.resolver("symbol", Tag.inserting(mm(symbol))))
                        .resolver(TagResolver.resolver("gains", Tag.inserting(mm(formatAmount(gains - position.commission)))))
                        .resolver(TagResolver.resolver("money_received", Tag.inserting(mm(formatAmount(gains +position.startPrice*position.amount)))))
                        .build();
                player.sendMessage(mm(StockConfig.string("message.closed-"+reason), resolver));
            }
            //System.out.println("Your total gains: " + (gains - position.commission)+" | commission: "+position.commission);
        }, () -> System.out.println("Could not find position with such id"));

    }


    public static boolean addToTradingBalanceFromVault(StockPlayer stockPlayer, double amount) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(stockPlayer.playerUuid);

        if (amount > 0) {
            if (ARC.getEcon().withdrawPlayer(offlinePlayer, amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false);
                //if (offlinePlayer.isOnline()) ((Player) offlinePlayer).sendMessage("Money added to your account!");
                return true;
            }

            //if (offlinePlayer.isOnline()) ((Player) offlinePlayer).sendMessage("You dont have enough money!");
        } else {
            if (stockPlayer.getBalance() < -amount) {
                //if (offlinePlayer.isOnline()) ((Player) offlinePlayer).sendMessage("You dont have enough money!");
                return false;
            }
            if (ARC.getEcon().depositPlayer(offlinePlayer, -amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false);
                return true;
            }
        }
        return false;
    }

    public static void switchAuto(StockPlayer stockPlayer) {
        stockPlayer.setAutoTake(!stockPlayer.autoTake);
        //System.out.println("Switched to "+stockPlayer.autoTake);
        saveStockPlayer(stockPlayer);
    }

    public record EconomyCheckResponse(boolean success, double totalPrice, double lack, double commission) {
    }
    public static EconomyCheckResponse economyCheck(StockPlayer player, Stock stock, double amount, int leverage) {
        double cost = cost(stock, amount);
        double commission = commission(stock, amount, leverage);
        double balance = player.getBalance();
        if (balance < cost + commission) {
            //if (player != null) TextUtil.noMoneyMessage(player, stock.price - balance);
            return new EconomyCheckResponse(false, cost + commission, cost + commission - balance, commission);
        }

        return new EconomyCheckResponse(true, cost + commission, 0, commission);
    }

    public static double cost(Stock stock, double amount){
        return stock.price * amount;
    }

    public static double commission(Stock stock, double amount, int leverage){
        return cost(stock, amount) * StockConfig.commission * (leverage < 10 ? 1 : 1 + Math.pow(leverage, 0.6)-Math.pow(10, 0.6));
    }

    public static void loadStockPlayers() {
        ARC.redisManager.loadMap("arc.stock_players")
                .thenAccept(map -> {
                    for (var entry : map.entrySet()) {
                        try {
                            StockPlayer stockPlayer = new ObjectMapper().readValue(entry.getValue(), StockPlayer.class);
                            playerMap.put(stockPlayer.playerUuid, stockPlayer);
                            System.out.println("Loaded stock data of " + stockPlayer.playerName);
                        } catch (JsonProcessingException e) {
                            System.out.println("Could not load stock data of " + entry.getKey());
                        }
                    }
                });
    }

    public static void loadStockPlayer(String uuid) {
        ARC.redisManager.loadMapEntry("arc.stock_players", uuid)
                .thenAccept(list -> {
                    if (list == null || list.isEmpty()) {
                        playerMap.remove(UUID.fromString(uuid));
                        return;
                    }
                    String json = list.get(0);
                    try {
                        StockPlayer stockPlayer = new ObjectMapper().readValue(json, StockPlayer.class);
                        playerMap.put(UUID.fromString(uuid), stockPlayer);
                        System.out.println("Loaded player " + stockPlayer.playerName + " data!");
                    } catch (JsonProcessingException e) {
                        System.out.println("Could not load data2 of " + uuid);
                        throw new RuntimeException(e);
                    }
                });
    }

    public static void saveStockPlayers() {
        StockPlayerManager.getAll().values().stream()
                .forEach(StockPlayerManager::saveStockPlayer);
    }

    public static void saveStockPlayer(StockPlayer stockPlayer) {
        if (!stockPlayer.isDirty()) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                return new ObjectMapper().writeValueAsString(stockPlayer);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(json -> {
            ARC.redisManager.saveMapKey("arc.stock_players", stockPlayer.playerUuid.toString(), json);
            stockPlayer.setDirty(false);
            new BukkitRunnable() {
                @Override
                public void run() {
                    messager.send(stockPlayer.playerUuid.toString());
                }
            }.runTaskLaterAsynchronously(ARC.plugin, 5L);
        });
    }


    public static StockPlayer getPlayer(UUID uuid) {
        return playerMap.get(uuid);
    }


    public static Map<UUID, StockPlayer> getAll() {
        return playerMap;
    }

    public static void setMap(Map<UUID, StockPlayer> map) {
        playerMap.putAll(map);
    }
}
