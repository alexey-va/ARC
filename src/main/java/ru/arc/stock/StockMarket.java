package ru.arc.stock;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.configs.StockConfig;
import ru.arc.network.repos.RedisRepo;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@RequiredArgsConstructor
public class StockMarket {
    private static BukkitTask updateTask, dividendTask;
    @Setter
    private static StockClient client;
    //private static Map<String, Stock> stockMap = new ConcurrentHashMap<>();

    private static RedisRepo<Stock> repo;
    private static Map<String, ConfigStock> configStocks = new ConcurrentHashMap<>();
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "stocks/stock.yml");

    public static void init() {
        if(!config.bool("enabled", false)) {
            info("Stocks are disabled");
            return;
        }
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
        HistoryManager.init();
    }

    public static void startTasks() {
        cancelTasks();

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                //// Logging removed - was using @Slf4j
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
                        error("Error fetching data for: {}", entry.getKey(), e);
                    }
                }

                // Logging removed - was using @Slf4j

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
                                error("Price for " + symbol + " is invalid: " + price);
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
                                error("Dividend for " + symbol + " is invalid: " + current.dividend);
                                current.dividend = 0;
                            }
                        }
                        current.setDirty(true);

                        // Logging removed - was using @Slf4j

                        StockPlayerManager.updateAllPositionsOf(symbol);
                    } catch (Exception e) {
                        error("Error updating stock: {}", entry.getKey(), e);
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
        if(!config.bool("enabled", false)) {
            info("Stocks are disabled");
            return;
        }
        HistoryManager.saveHistory();
    }

    public static void cancelTasks() {
        if(!config.bool("enabled", false)) {
            info("Stocks are disabled");
            return;
        }
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

    @SuppressWarnings("unchecked")
    public static void loadStockFromMap(Map<?, ?> map) {
        try {
            ConfigStock stock = ConfigStock.deserialize((Map<String, Object>) map);
            stock.setSymbol(stock.getSymbol().toUpperCase());
            configStocks.put(stock.getSymbol(), stock);

            if (repo == null) return;

            var current = repo.getNow(stock.getSymbol());
            if (current == null) return;
            current.lore = stock.lore;
            current.display = stock.display;
            current.icon = stock.icon;
            current.maxLeverage = stock.maxLeverage;
            current.type = stock.type;
            current.setDirty(true);
        } catch (Exception e) {
            error("Error loading stock from map: {}", map, e);
        }
    }

    public static boolean isEnabledStock(Stock stock) {
        if (stock == null) return false;
        return configStocks().stream().map(ConfigStock::getSymbol).collect(Collectors.toSet()).contains(stock.getSymbol());
    }


    public static Collection<ConfigStock> configStocks() {
        return configStocks.values();
    }
}
