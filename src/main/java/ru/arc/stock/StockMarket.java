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
import ru.arc.repository.LegacyRedisRepo;

import static ru.arc.util.Logging.debug;
import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

@RequiredArgsConstructor
public class StockMarket {
    private static BukkitTask updateTask, dividendTask;
    @Setter
    private static StockClient client;
    //private static Map<String, Stock> stockMap = new ConcurrentHashMap<>();

    private static final Map<String, ConfigStock> configStocks = new ConcurrentHashMap<>();
    private static final Config config = ConfigManager.of(ARC.getInstance().getDataPath(), "stocks/stock.yml");
    private static LegacyRedisRepo<Stock> repo;

    public static void init() {
        if(!config.bool("enabled", false)) {
            info("Stocks are disabled");
            return;
        }
        if (repo == null) {
            repo = LegacyRedisRepo.builder(Stock.class)
                    .loadAll(true)
                    .redisManager(ARC.redisManager)
                    .storageKey("arc.stocks")
                    .updateChannel("arc.stocks_update")
                    .id("stocks")
                    .backupFolder(ARC.getInstance().getDataFolder().toPath().resolve("backups/stocks"))
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
                        long lastUpdated = current == null ? 0 : current.getLastUpdated();
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
                            if (current.getPrice() < 0 || current.getPrice() > 1_000_000) {
                                error("Price for " + symbol + " is invalid: " + price);
                                continue;
                            }
                            price = current.getPrice();
                        }
                        HistoryManager.add(symbol, price);

                        current.setPrice(price);
                        current.setLastUpdated(System.currentTimeMillis());
                        if (current.getType() == Stock.Type.STOCK) {
                            current.setDividend(current.getPrice() * StockConfig.dividendPercentFromPrice);
                            if (current.getDividend() > 10_000) {
                                error("Dividend for " + symbol + " is invalid: " + current.getDividend());
                                current.setDividend(0);
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
        }.runTaskTimerAsynchronously(ARC.getInstance(), 20L, 10 * 20L);

        dividendTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                stocks().stream()
                        .filter(stock -> stock.getDividend() > 0.000001)
                        .filter(s -> System.currentTimeMillis() - s.getLastTimeDividend() >= 23 * 60 * 60 * 1000L)
                        .peek(stock -> stock.setLastTimeDividend(System.currentTimeMillis()))
                        .peek(stock -> stock.setDirty(true))
                        .map(Stock::getSymbol)
                        .forEach(StockPlayerManager::giveDividend);
            }
        }.runTaskTimer(ARC.getInstance(), 100L, 20L * 60);
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
            current.setLore(stock.getLore());
            current.setDisplay(stock.getDisplay());
            current.setIcon(stock.getIcon());
            current.setMaxLeverage(stock.getMaxLeverage());
            current.setType(stock.getType());
            current.setDirty(true);
        } catch (Exception e) {
            error("Error loading stock from map: {}", map, e);
            debug("[stock] failed to deserialize stock entry keys={}", map != null ? map.keySet() : null);
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
