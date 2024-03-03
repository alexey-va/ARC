package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class StockMarket {
    private static BukkitTask updateTask, dividendTask;
    static ExecutorService service = Executors.newSingleThreadExecutor();

    @Setter
    private static StockMessager stockMessager;
    @Setter
    private static StockClient client;
    private static Map<String, Stock> stockMap = new ConcurrentHashMap<>();


    public static void startTasks() {
        cancelTasks();
        HistoryManager.startTasks();

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                boolean change = false;
                Map<String, Double> updates = new HashMap<>();

                boolean fetchedCrypto = false;
                for (var entry : stockMap.entrySet()) {
                    try {
                        if (System.currentTimeMillis() - entry.getValue().lastUpdated > StockConfig.stockRefreshRate * 1000L) {
                            if (entry.getValue().type == Stock.Type.CRYPTO) {
                                if (fetchedCrypto) continue;
                                updates.putAll(client.cryptoPrices());
                                fetchedCrypto = true;
                                continue;
                            }
                            updates.put(entry.getKey(), client.price(entry.getValue()));
                        }
                    } catch (Exception e){
                        System.out.println("Error fetching data for: "+entry.getKey());
                        e.printStackTrace();
                    }
                }

                for (var entry : updates.entrySet()) {
                    String symbol = entry.getKey().toUpperCase();
                    double price = entry.getValue();
                    Stock stock = stockMap.get(symbol);
                    if (stock == null) {
                        System.out.println("Could not find stock: " + symbol + " while updating prices!");
                        continue;
                    }
                    if (price < 0 || price > 1_000_000) {
                        price = stock.price;
                    }
                    HistoryManager.add(symbol, price);

                    //System.out.println(symbol + " -> " + price);
                    stock.price = price;
                    stock.lastUpdated = System.currentTimeMillis();
                    StockPlayerManager.updateAllPositionsOf(symbol);
                    change = true;
                }

                if (change) saveStocks(updates.keySet());
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 10 * 20L);

        dividendTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                Set<String> strings = new HashSet<>();
                StockMarket.stocks().stream()
                        .filter(stock -> stock.dividend > 0.000001)
                        .filter(s -> System.currentTimeMillis() - s.lastTimeDividend >= 23 * 60 * 60 * 1000L)
                        .map(Stock::getSymbol)
                        .peek(strings::add)
                        .forEach(StockPlayerManager::giveDividend);

                saveStocks(strings);
            }
        }.runTaskTimer(ARC.plugin, 100L, 20L * 60);
    }

    public static void saveStocks(Collection<String> symbols) {
        if (stockMessager != null) {
            service.submit(() -> stockMessager.send(stockMap.entrySet().stream()
                    .filter(e -> symbols.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            ));
        }
    }

    public static void saveHistory() {
        HistoryManager.saveHistory();
    }

    public static void cancelTasks() {
        if (updateTask != null && updateTask.isCancelled()) updateTask.cancel();
        HistoryManager.cancelTasks();
    }


    public static Stock stock(String symbol) {
        return stockMap.get(symbol);
    }

    public static Collection<Stock> stocks() {
        return stockMap.values();
    }

    public static void loadStockFromMap(Map<?, ?> map) {
        try {
            System.out.println("Parsed stock: " + map.get("symbol"));
            Stock stock = Stock.deserialize((Map<String, Object>) map);
            if (stock.type == Stock.Type.STOCK) {
                stock.setDividend(stock.price * StockConfig.dividendPercentFromPrice);
                if (stock.getDividend() > 1000) stock.setDividend(0);
            }
            stock.setSymbol(stock.getSymbol().toUpperCase());
            stockMap.put(stock.symbol, stock);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void loadCacheFromMap(Map<?, ?> map) {
        try {
            //System.out.println("Loading from cache: "+map);
            Stock cachedStock = Stock.deserialize((Map<String, Object>) map);
            Stock actualStock = stockMap.get(cachedStock.symbol);
            if (actualStock == null) {
                System.out.println("Stock " + cachedStock + " in cache but not in config! Skipping...");
                return;
            }
            actualStock.lastUpdated = cachedStock.lastUpdated;
            actualStock.price = cachedStock.price;
            actualStock.lastTimeDividend = cachedStock.lastTimeDividend;
            System.out.println("Cached last dividend: "+cachedStock.lastTimeDividend);
            if (actualStock.type == Stock.Type.STOCK) {
                actualStock.setDividend(cachedStock.price * StockConfig.dividendPercentFromPrice);
                if (cachedStock.getDividend() > 1000) cachedStock.setDividend(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void setMap(Map<String, Stock> map) {
        stockMap.putAll(map);
    }

    public static void setMessager(StockMessager messager) {
        stockMessager = messager;
    }


}
