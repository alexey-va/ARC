package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.board.BoardEntry;
import arc.arc.configs.StockConfig;
import arc.arc.network.repos.RedisRepo;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jsoup.nodes.Node;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Log4j2
public class StockMarket {
    private static BukkitTask updateTask, dividendTask;
    @Setter
    private static StockClient client;
    //private static Map<String, Stock> stockMap = new ConcurrentHashMap<>();

    private static RedisRepo<Stock> repo;
    private static Map<String, ConfigStock> configStocks = new ConcurrentHashMap<>();

    public static void init() {
        if (repo == null) {
            repo = RedisRepo.builder(Stock.class)
                    .loadAll(true)
                    .redisManager(ARC.redisManager)
                    .storageKey("arc.stocks")
                    .updateChannel("arc.stocks_update")
                    .id("stocks")
                    .backupFolder(ARC.plugin.getDataFolder().toPath().resolve("backups/stocks"))
                    .saveInterval(20L)
                    .build();
        }

        startTasks();
    }

    public static void startTasks() {
        cancelTasks();
        HistoryManager.startTasks();

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                //log.trace("Updating stocks: "+configStocks.values());
                Map<String, Double> updates = new HashMap<>();

                boolean fetchedCrypto = false;
                for (var entry : configStocks.entrySet()) {
                    try {
                        Stock current = repo.getNow(entry.getKey());
                        long lastUpdated = current == null ? 0 : current.lastUpdated;
                        if (System.currentTimeMillis() - lastUpdated > StockConfig.stockRefreshRate * 1000L) {
                            if (entry.getValue().type == Stock.Type.CRYPTO) {
                                if (fetchedCrypto) continue;
                                updates.putAll(client.cryptoPrices());
                                fetchedCrypto = true;
                                continue;
                            }
                            updates.put(entry.getKey(), client.price(entry.getValue()));
                        }
                    } catch (Exception e) {
                        log.error("Error fetching data for: " + entry.getKey());
                        e.printStackTrace();
                    }
                }

                log.trace("Fetched updates: " + updates);

                for (var entry : updates.entrySet()) {
                    try {
                        String symbol = entry.getKey().toUpperCase();
                        double price = entry.getValue();
                        double finalPrice = price;
                        Stock current = repo
                                .getOrCreate(symbol, () -> configStocks.get(symbol)
                                        .toStock(finalPrice, 0, System.currentTimeMillis(), 0))
                                .get();


                        if (price < 0 || price > 1_000_000) {
                            if (current.price < 0 || current.price > 1_000_000) {
                                log.error("Price for " + symbol + " is invalid: " + price);
                                continue;
                            }
                            price = current.price;
                        }
                        HistoryManager.add(symbol, price);

                        current.price = price;
                        current.lastUpdated = System.currentTimeMillis();
                        if (current.type == Stock.Type.STOCK) {
                            current.dividend = current.price * StockConfig.dividendPercentFromPrice;
                            if (current.dividend > 10_000) {
                                log.error("Dividend for " + symbol + " is invalid: " + current.dividend);
                                current.dividend = 0;
                            }
                        }
                        current.setDirty(true);

                        log.trace("Updated stock: " + symbol + " to " + price);

                        StockPlayerManager.updateAllPositionsOf(symbol);
                    } catch (Exception e) {
                        log.error("Error updating stock: " + entry.getKey());
                        e.printStackTrace();
                    }

                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 10 * 20L);

        dividendTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                stocks().stream()
                        .filter(stock -> stock.dividend > 0.000001)
                        .filter(s -> System.currentTimeMillis() - s.lastTimeDividend >= 23 * 60 * 60 * 1000L)
                        .peek(stock -> stock.lastTimeDividend = System.currentTimeMillis())
                        .peek(stock -> stock.setDirty(true))
                        .map(Stock::getSymbol)
                        .forEach(StockPlayerManager::giveDividend);
            }
        }.runTaskTimer(ARC.plugin, 100L, 20L * 60);
    }

    public static void saveHistory() {
        HistoryManager.saveHistory();
    }

    public static void cancelTasks() {
        if (updateTask != null && updateTask.isCancelled()) updateTask.cancel();
        if (dividendTask != null && dividendTask.isCancelled()) dividendTask.cancel();
        HistoryManager.cancelTasks();
    }


    public static Stock stock(String symbol) {
        return repo.getNow(symbol);
    }

    public static Collection<Stock> stocks() {
        return repo.all();
    }

    public static void loadStockFromMap(Map<?, ?> map) {
        try {
            ConfigStock stock = ConfigStock.deserialize((Map<String, Object>) map);
            stock.setSymbol(stock.getSymbol().toUpperCase());
            configStocks.put(stock.getSymbol(), stock);

            var current = repo.getNow(stock.getSymbol());
            if(current == null) return;
            current.lore = stock.lore;
            current.display = stock.display;
            current.icon = stock.icon;
            current.maxLeverage = stock.maxLeverage;
            current.type = stock.type;
            current.setDirty(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    public static Collection<ConfigStock> configStocks() {
        return configStocks.values();
    }
}
