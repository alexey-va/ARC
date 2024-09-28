package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.network.repos.RedisRepo;
import arc.arc.util.TextUtil;
import arc.arc.util.Utils;
import arc.arc.xserver.announcements.AnnounceManager;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static arc.arc.util.TextUtil.formatAmount;
import static arc.arc.util.TextUtil.mm;

@Log4j2
public class StockPlayerManager {
    private static RedisRepo<StockPlayer> repo;

    public static void init() {
        if (repo != null) repo.close();
        repo = RedisRepo.builder(StockPlayer.class)
                .loadAll(true)
                .redisManager(ARC.redisManager)
                .storageKey("arc.stock_players")
                .updateChannel("arc.stock_players_update")
                .id("stock_players")
                .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/stock-players"))
                .saveInterval(5L)
                .saveBackups(true)
                .build();
    }

    public static void updateAllPositionsOf(String symbol) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            System.out.println("Stock " + symbol + " is null while trying to update positions!");
            return;
        }

        repo.all().stream().filter(sp -> sp.positionMap.containsKey(symbol))
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


    public static void giveDividend(String symbol) {
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) return;
        System.out.println("Giving dividend for " + stock.symbol + ": " + Instant.ofEpochMilli(stock.lastTimeDividend));
        stock.setLastTimeDividend(System.currentTimeMillis());
        for (StockPlayer stockPlayer : repo.all()) {
            double gave = stockPlayer.giveDividend(symbol);
            if (gave <= 0.1) continue;
            String message = StockConfig.string("message.received-dividend")
                    .replace("<amount>", TextUtil.formatAmount(gave))
                    .replace("<symbol>", symbol);
            AnnounceManager.sendMessage(stockPlayer.playerUuid, message);
        }
    }


    public static CompletableFuture<StockPlayer> getOrCreate(Player player) {
        return repo.getOrCreate(player.getUniqueId().toString(), () ->
                new StockPlayer(player.getName(), player.getUniqueId())
        );
    }

    public static void buyStock(StockPlayer stockPlayer, Stock stock, double amount, int leverage, double lowerBound, double upperBound) {
        List<Position> stockPositions = stockPlayer.positions(stock.symbol);
        boolean canHaveMore = stockPlayer.isBelowMaxStockAmount() && !(stockPositions != null && stockPositions.size() >= 9);
        if (!canHaveMore || stockPlayer.positions().size() >= 30) {
            Player p = stockPlayer.player();
            if (p == null) return;
            p.sendMessage(mm(StockConfig.string("message.too-many-positions")));
            return;
        }

        EconomyCheckResponse response = economyCheck(stockPlayer, stock, amount, leverage);
        if (!response.success()) {
            log.trace("Economy check failed: " + response);
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
        log.trace("Successfully added position: {}", position);
    }

    public static void shortStock(StockPlayer stockPlayer, Stock stock, double amount, int leverage, double lowerBound, double upperBound) {
        List<Position> stockPositions = stockPlayer.positions(stock.symbol);
        boolean canHaveMore = stockPlayer.isBelowMaxStockAmount() && !(stockPositions != null && stockPositions.size() >= 9);
        if (!canHaveMore || stockPlayer.positions().size() >= 30) {
            Player p = stockPlayer.player();
            if (p == null) return;
            p.sendMessage(mm(StockConfig.string("message.too-many-positions")));
            return;
        }

        EconomyCheckResponse response = economyCheck(stockPlayer, stock, amount, leverage);
        if (!response.success()) {
            log.trace("Economy check failed: " + response);
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
        log.trace("Successfully added position: {}", position);
    }

    public static void closePosition(StockPlayer stockPlayer, String symbol, UUID positionUuid, int reason) {
        log.trace("Closing position with uuid: {} of {}", positionUuid, stockPlayer.getPlayerName());
        Stock stock = StockMarket.stock(symbol);
        if (stock == null) {
            log.error("Could not find stock with symbol: " + symbol);
            return;
        }
        stockPlayer.remove(symbol, positionUuid).ifPresentOrElse(position -> {
            double gains = position.gains(stock.price);
            log.trace("Gains: {}", gains);
            stockPlayer.addToBalance(gains + position.startPrice * position.amount, true);
            String message = StockConfig.string("message.closed-" + reason)
                    .replace("<gains>", formatAmount(gains - position.commission))
                    .replace("<symbol>", symbol)
                    .replace("<money_received>", formatAmount(gains + position.startPrice * position.amount));
            AnnounceManager.sendMessage(stockPlayer.playerUuid, message);
        }, () -> log.error("Could not find position with such id {}", positionUuid));

    }


    public static boolean addToTradingBalanceFromVault(StockPlayer stockPlayer, double amount) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(stockPlayer.playerUuid);
        log.trace("Adding {} to trading balance of {}", amount, stockPlayer.getPlayerName());

        if (amount > 0) {
            if (ARC.getEcon().withdrawPlayer(offlinePlayer, amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false);
                log.trace("Successfully added {} to trading balance of {}", amount, stockPlayer.getPlayerName());
                return true;
            }
            log.trace("Failed to add {} to trading balance of {}", amount, stockPlayer.getPlayerName());
        } else {
            if (stockPlayer.getBalance() < -amount) {
                log.trace("Not enough money to take {} from trading balance of {}", amount, stockPlayer.getPlayerName());
                return false;
            }
            if (ARC.getEcon().depositPlayer(offlinePlayer, -amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false);
                log.trace("Successfully took {} from trading balance of {}", amount, stockPlayer.getPlayerName());
                return true;
            }
        }
        log.trace("Failed to take {} from trading balance of {}", amount, stockPlayer.getPlayerName());
        return false;
    }

    public static void switchAuto(StockPlayer stockPlayer) {
        stockPlayer.setAutoTake(!stockPlayer.autoTake);
        log.trace("Switched auto take for {} to {}", stockPlayer.getPlayerName(), stockPlayer.autoTake);
    }

    public static CompletableFuture<StockPlayer> getOrNull(UUID uniqueId) {
        return repo.getOrNull(uniqueId.toString());
    }

    public static StockPlayer getNow(UUID uniqueId) {
        return repo.getNow(uniqueId.toString());
    }

    public record EconomyCheckResponse(boolean success, double totalPrice, double lack, double commission) {
    }

    public static EconomyCheckResponse economyCheck(StockPlayer player, Stock stock, double amount, int leverage) {
        double cost = cost(stock, amount);
        double commission = commission(stock, amount, leverage);
        double balance = player.getBalance();
        if (balance < cost + commission) {
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

}
