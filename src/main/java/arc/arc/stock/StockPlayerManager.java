package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.util.TextUtil;
import arc.arc.util.Utils;
import arc.arc.xserver.announcements.AnnounceManager;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static arc.arc.util.TextUtil.formatAmount;

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
                System.out.println("Bankrupt for " + position);
                if (stockPlayer.isAutoTake()) {
                    boolean addedToBalanceSuccess = addToTradingBalanceFromVault(stockPlayer, response.total());
                    if (addedToBalanceSuccess) continue;
                }
                closePosition(stockPlayer, symbol, position.getPositionUuid(), 2);
            }

            int marginCall = position.marginCall(stock.getPrice());
            if (marginCall != 0) {
                System.out.println("Margin call " + marginCall + " for " + position);
                closePosition(stockPlayer, position.getSymbol(), position.getPositionUuid(), 1);
            }
        }
    }

    public static void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
        if (dividendTask != null && !dividendTask.isCancelled()) dividendTask.cancel();
    }

    public static void giveDividend(String symbol) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) return;
        System.out.println("Giving dividend for "+stock.symbol+": "+ Instant.ofEpochMilli(stock.lastTimeDividend));
        stock.setLastTimeDividend(System.currentTimeMillis());
        for (StockPlayer stockPlayer : playerMap.values()) {
            double gave = stockPlayer.giveDividend(symbol);
            if(gave<=0.1) continue;
            String message = StockConfig.string("message.received-dividend")
                    .replace("<amount>", TextUtil.formatAmount(gave))
                    .replace("<symbol>", symbol);
            AnnounceManager.instance().sendMessage(stockPlayer.playerUuid, message);
        }
    }


    private static StockPlayer createPlayer(String playerName, UUID playerUuid) {
        System.out.println("Creating player for " + playerName);
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
        if (stockPlayer.positions().size() >= 30) {
            System.out.println("Too many positions!");
            return;
        }

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
                .iconMaterial(Utils.random(StockConfig.iconMaterials))
                .type(Position.Type.BOUGHT)
                .build();
        stockPlayer.addPosition(position);
        //player.sendMessage("Successfully added position: " + position);
    }

    public static void shortStock(StockPlayer stockPlayer, Stock stock, double amount, int leverage, double lowerBound, double upperBound) {
        if (stockPlayer.positions().size() >= 30) {
            System.out.println("Too many positions!");
            return;
        }

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
                .iconMaterial(Utils.random(StockConfig.iconMaterials))
                .type(Position.Type.SHORTED)
                .build();
        stockPlayer.addPosition(position);
        //player.sendMessage("Successfully added position: " + position);
    }

    public static void closePosition(StockPlayer stockPlayer, String symbol, UUID positionUuid, int reason) {
        System.out.println("Closing position " + positionUuid + " cuz " + reason);
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Could not find stock with symbol: " + symbol);
            return;
        }
        stockPlayer.remove(symbol, positionUuid).ifPresentOrElse(position -> {
            double gains = position.gains(stock.price);
            stockPlayer.addToBalance(gains + position.startPrice * position.amount, true);
            String message = StockConfig.string("message.closed-" + reason)
                    .replace("<gains>", formatAmount(gains - position.commission))
                    .replace("<symbol>", symbol)
                    .replace("<money_received>", formatAmount(gains + position.startPrice * position.amount));
            AnnounceManager.instance().sendMessage(stockPlayer.playerUuid, message);
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

    public static double cost(Stock stock, double amount) {
        return stock.price * amount;
    }

    public static double commission(Stock stock, double amount, int leverage) {
        return cost(stock, amount) * StockConfig.commission * (leverage < 100 ? 1 : 1 + Math.pow(leverage, StockConfig.leveragePower) - Math.pow(100, StockConfig.leveragePower));
    }

    public static void loadStockPlayers() {
        playerMap.putAll(messager.loadAllStockPlayers());
    }

    public static void loadStockPlayer(UUID uuid) {
        messager.loadStockPlayer(uuid);
    }

    public static void saveStockPlayers() {
        StockPlayerManager.getAll().values().stream()
                .filter(StockPlayer::isDirty)
                .forEach(StockPlayerManager::saveStockPlayer);
    }

    public static void saveStockPlayer(StockPlayer stockPlayer) {
        messager.saveStockPlayer(stockPlayer);
    }


    public static StockPlayer getPlayer(UUID uuid) {
        return playerMap.get(uuid);
    }

    public static Optional<StockPlayer> getPlayer(String name) {
        return playerMap.values().stream()
                .filter(sp -> sp.playerName.equals(name))
                .findAny();
    }


    public static Map<UUID, StockPlayer> getAll() {
        return playerMap;
    }

    public static void setMap(Map<UUID, StockPlayer> map) {
        playerMap.putAll(map);
    }
}
