package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.board.ItemIcon;
import arc.arc.configs.StockConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class StockMarket {
    private static BukkitTask updateTask;
    private static BukkitTask saveHistoryTask;
    @Setter
    private static StockMessager stockMessager;
    @Setter
    private static StockClient client;

    private static Map<String, Stock> stockMap = new ConcurrentHashMap<>();
    private static Map<String, List<StockHistory>> history = new ConcurrentHashMap<>();
    public static Map<String, CurrencyData> currencyDataMap = new HashMap<>();

    public static void pruneHistory(String symbol) {
        history.remove(symbol);
    }

    public record CurrencyData(String id, String display, List<String> lore, boolean crypto, ItemIcon icon) {
    }

    @SneakyThrows
    private static CurrencyData currencyData(Map<String, Object> map) {
        ItemIcon icon = ItemIcon.of(Material.PAPER, 0);
        if (map.containsKey("icon")) icon = new ObjectMapper().readValue((String) map.get("icon"), ItemIcon.class);
        return new CurrencyData(
                (String) (map.containsKey("id") ? map.get("id") : map.get("symbol")),
                (String) map.getOrDefault("display", "display"),
                (List<String>) map.getOrDefault("lore", new ArrayList<String>()),
                (Boolean) map.getOrDefault("crypto", false),
                icon
        );
    }

    public static void loadCurrencyFromMap(Map<?,?> map) {
        CurrencyData currencyData = currencyData((Map<String, Object>) map);
        System.out.println("Parsed currency: "+currencyData.id);
        currencyDataMap.put(currencyData.id, currencyData);
    }


    public record StockHistory(double cost, long timestamp) {
    }

    public static void startTasks() {
        cancelTasks();
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!StockConfig.mainServer) return;
                boolean change = false;


                if (!stockMap.containsKey("BITCOIN") || System.currentTimeMillis() - stockMap.get("BITCOIN").lastUpdated > StockConfig.refreshRate * 1000L) {
                    client.fetchCrypto().ifPresentOrElse(list -> list.forEach(stock -> {
                        try {
                            history.putIfAbsent(stock.symbol, new ArrayList<>());
                            StockHistory stockHistory = new StockHistory(stock.price, stock.lastUpdated);
                            //System.out.println(stockHistory);
                            history.get(stock.symbol).add(stockHistory);
                            stockMap.put(stock.symbol, stock);

                            StockPlayerManager.updateAllPositionsOf(stock.symbol);
                            //System.out.println("Updated stock: " + stock);
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.out.println("Could not load " + stock);
                        }

                    }), () -> System.out.println("Could not load stock for crypto"));
                    change = true;
                }

                for (var entry : stockMap.entrySet()) {
                    if (!entry.getValue().isStock()) continue;
                    if (System.currentTimeMillis() - entry.getValue().lastUpdated > StockConfig.stockRefreshRate * 1000L) {
                        try {
                            Double newPrice = client.price(entry.getKey());
                            if(newPrice == null || newPrice == 0) newPrice = entry.getValue().price;
                            //if (newPrice == entry.getValue().price) continue;
                            if(newPrice > 10000) {
                                System.out.println("New price is extremely high (>1000) for "+entry.getValue());
                                continue;
                            }
                            entry.getValue().price = newPrice;
                            entry.getValue().lastUpdated = System.currentTimeMillis();
                            history.putIfAbsent(entry.getKey(), new ArrayList<>());
                            history.get(entry.getKey()).add(new StockHistory(entry.getValue().price, entry.getValue().lastUpdated));
                            //System.out.println("Updated stock: " + entry.getValue().symbol + " -> " + entry.getValue().price);

                            StockPlayerManager.updateAllPositionsOf(entry.getValue().symbol);
                        } catch (Exception e) {
                            System.out.println("Could not update stock: " + entry.getKey());
                            e.printStackTrace();
                        }
                        change = true;
                    }
                }

                if (stockMessager != null && change) stockMessager.send(stockMap);
                //StockConfig.saveStockCache(stockMap.values().stream().map(Stock::serialize).toList());
                //StockConfig.saveStockHistory(history);
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 10 * 20L);

        saveHistoryTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveHistory();
                StockConfig.saveStockCache(stockMap.values().stream().map(Stock::serialize).toList());
                String path = ARC.plugin.getDataFolder() + File.separator + "stocks" + File.separator + "plots.sh";
                long time = System.currentTimeMillis();
                File file = new File(path);
                if (file.exists()) {
                    //System.out.println(file.getPath());
                    ProcessBuilder processBuilder = new ProcessBuilder(path);
                    processBuilder.redirectErrorStream(true);
                    try {
                        Process process = processBuilder.start();

                        InputStream inputStream = process.getInputStream();
                        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            System.out.println(line);
                        }
                        System.out.println("Plotting took: "+(System.currentTimeMillis()-time)+"ms");
                        Bukkit.getScheduler().runTask(ARC.plugin,
                                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "arc-invest -t:update"));
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                } else {
                    System.out.println(path + " does not exist! Not updating plots!");
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 100L, 20L * 300);
    }

    public static void saveHistory() {
        long current = System.currentTimeMillis();
        for (var list : history.values()) {
            list.removeIf(sh -> current - sh.timestamp > 1000L * 60 * 60 * 24);
            Set<Long> seen = new HashSet<>();
            List<StockHistory> stockHistories = new ArrayList<>();
            for (var e : list) {
                if (!seen.add(e.timestamp)) stockHistories.add(e);
            }
            list.removeAll(stockHistories);
        }
        StockConfig.saveStockHistory(history);
        System.out.println("Saving took: "+(System.currentTimeMillis()-current)+"ms");
    }

    public static void cancelTasks() {
        if (updateTask != null && updateTask.isCancelled()) updateTask.cancel();
        if (saveHistoryTask != null && !saveHistoryTask.isCancelled()) saveHistoryTask.cancel();
    }


    public static Stock stock(String symbol) {
        return stockMap.get(symbol);
    }

    public static void loadStockFromMap(Map<?, ?> map) {
        try {
            System.out.println("Parsed stock: "+map.get("symbol"));
            Stock stock = Stock.deserialize((Map<String, Object>) map);
            stock.setStock(true);
            if(true){
                stock.setDividend(stock.price*0.03);
            }
            stockMap.put(stock.symbol, stock);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static void loadCacheFromMap(Map<?,?> map) {
        try {
            //System.out.println("Loading from cache: "+map);
            Stock stock = Stock.deserialize((Map<String, Object>) map);
            Stock stock1 = stockMap.get(stock.symbol);
            if(stock1 == null){
                System.out.println("Stock "+stock+" in cache but not in config! Skipping...");
                return;
            }

            stock1.lastUpdated=stock.lastUpdated;
            stock1.price=stock.price;
            if(stock1.isStock){
                stock1.setDividend(stock.price*0.03);
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

    public static List<Stock> stocks() {
        return new ArrayList<>(stockMap.values());
    }

    public static void setHistory(Map<String, List<StockHistory>> history) {
        StockMarket.history.putAll(history);
    }
}
