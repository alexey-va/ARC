package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.StockConfig;
import arc.arc.util.Common;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class HistoryManager {

    private static BukkitTask saveTask;
    private static final String SCRIPT_FILE = "plots.sh";
    private static Map<String, List<StockHistory>> history = new ConcurrentHashMap<>();
    private static final Map<String, HighLow> highLows = new ConcurrentHashMap<>();
    @Setter
    private static HistoryMessager messager;

    public static void setHighLows(Map<String, HighLow> highLowMap) {
        highLows.clear();
        highLows.putAll(highLowMap);
    }

    public record StockHistory(double cost, long timestamp) {
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HighLow {
        double high, low;
    }

    public static void startTasks() {
        cancelTasks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!StockConfig.mainServer) return;
                    saveHistory();
                    drawPlots(true);
                    messager.send(highLows);
                } catch (Exception e) {
                    log.error("Error in saveTask", e);
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 100L, 20L * 300);
    }

    static void drawPlots(boolean sendPackets) {
        String path = ARC.plugin.getDataFolder() + File.separator + "stocks" + File.separator + SCRIPT_FILE;
        long time = System.currentTimeMillis();
        File file = new File(path);
        if (file.exists()) {
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
                System.out.println("Plotting took: " + (System.currentTimeMillis() - time) + "ms");
                if (sendPackets) Bukkit.getScheduler().runTask(ARC.plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "arc-invest -t:update"));
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        } else {
            System.out.println(path + " does not exist! Not updating plots!");
        }
    }

    static void cancelTasks() {
        if (saveTask != null && !saveTask.isCancelled()) saveTask.cancel();
    }

    public static void pruneHistory(String symbol) {
        history.remove(symbol);
        saveHistory();
    }

    static void saveHistory() {
        evictOldHistory();
        StockConfig.saveStockHistory();
    }

    static void evictOldHistory() {
        long current = System.currentTimeMillis();
        for (var list : history.values()) {
            list.removeIf(sh -> current - sh.timestamp > 1000L * StockConfig.historyLifetime);
            Set<Long> seen = new HashSet<>();
            List<StockHistory> stockHistories = new ArrayList<>();
            for (var e : list) {
                if (!seen.add(e.timestamp)) stockHistories.add(e);
            }
            list.removeAll(stockHistories);
        }
    }

    static void add(String symbol, double price) {
        add(symbol, price, System.currentTimeMillis());
    }

    static void add(String symbol, double price, long timestamp) {
        history.merge(symbol, new ArrayList<>(List.of(new StockHistory(price, timestamp))),
                (existingList, list) -> {
                    existingList.addAll(list);
                    return existingList;
                });

        highLows.merge(symbol, new HighLow(price, price),
                (old, highLow) -> new HighLow(Math.max(old.high, highLow.high), Math.min(old.low, highLow.low)));
    }

    static double high(String symbol) {
        HighLow highLow = highLows.get(symbol);
        if (highLow == null) return 0;
        return highLow.high;
    }

    static double low(String symbol) {
        HighLow highLow = highLows.get(symbol);
        if (highLow == null) return 0;
        return highLow.low;
    }

    public static void loadFromFile(File file) {
        try {
            TypeToken<Map<String, List<HistoryManager.StockHistory>>> token = new TypeToken<>() {
            };
            Map<String, List<HistoryManager.StockHistory>> history = Common.gson.fromJson(Files.newBufferedReader(file.toPath()), token);
            log.info("Loaded history: {}", history.values().stream().mapToInt(List::size).sum());
            appendHistory(history);
        } catch (Exception e) {
            log.error("Error loading history", e);
            history = new ConcurrentHashMap<>();
            StockConfig.saveStockHistory();
        }
    }

    public static void saveToFile(File file) {
        try {
            String json = Common.prettyGson.toJson(history);
            //if (!Files.exists(file.toPath())) Files.createFile(file.toPath());
            Files.write(file.toPath(), json.getBytes());
        } catch (IOException e) {
            log.error("Error saving history", e);
            throw new RuntimeException(e);
        }
    }

    public static void appendHistory(Map<String, List<StockHistory>> history) {
        for (var entry : history.entrySet()) {
            for (var stockHistory : entry.getValue()) {
                add(entry.getKey(), stockHistory.cost, stockHistory.timestamp);
            }
        }
    }

}
