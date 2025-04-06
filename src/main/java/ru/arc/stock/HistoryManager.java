package ru.arc.stock;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.configs.StockConfig;
import ru.arc.util.Common;
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
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class HistoryManager {

    private static BukkitTask saveTask;
    private static final String SCRIPT_FILE = "plots.sh";
    private static final Map<String, List<StockHistory>> history = new ConcurrentHashMap<>();
    private static final Map<String, HighLow> highLows = new ConcurrentHashMap<>();
    @Setter
    private static HistoryMessager messager;
    private static Path historyPath;
    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "stocks/stock.yml");

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

    public static void init() {
        if(!config.bool("enabled", false)) {
            log.info("Stocks are disabled");
            return;
        }
        historyPath = ARC.plugin.getDataFolder().toPath().resolve("stocks/history.json");
        if (!Files.exists(historyPath)) {
            try {
                Files.createDirectories(historyPath.getParent());
                Files.createFile(historyPath);
            } catch (IOException e) {
                log.error("Error creating history file", e);
            }
        }

        loadFromFile();
        startTasks();
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
                        () -> ARC.trySeverCommand("arc-invest -t:update"));
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
        if(!config.bool("enabled", false)) {
            log.info("Stocks are disabled");
            return;
        }
        log.info("Saving history size {}", history.values().stream().mapToInt(List::size).sum());
        evictOldHistory();
        saveToFile();
    }

    static void evictOldHistory() {
        long current = System.currentTimeMillis();
        for (var list : history.values()) {
            //log.info("Evicting old history entries: {}", list.size());
            list.removeIf(sh -> current - sh.timestamp > 1000L * StockConfig.historyLifetime);
            Set<Long> seen = new HashSet<>();
            List<StockHistory> duplicates = new ArrayList<>();
            for (var e : list) {
                if (!seen.add(e.timestamp)) duplicates.add(e);
            }
            list.removeAll(duplicates);
            //log.info("Evicted old history entries: {}", list.size());
        }
    }

    static void add(String symbol, double price) {
        add(symbol, price, System.currentTimeMillis());
    }

    static void add(String symbol, double price, long timestamp) {
        history.compute(symbol, (s, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(new StockHistory(price, timestamp));
            return list;
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

    public static void loadFromFile() {
        try {
            TypeToken<Map<String, List<HistoryManager.StockHistory>>> token = new TypeToken<>() {
            };
            BufferedReader bufferedReader = Files.newBufferedReader(historyPath);
            Map<String, List<HistoryManager.StockHistory>> history = Common.gson.fromJson(bufferedReader, token);
            bufferedReader.close();
            log.info("Loaded history: {}", history.values().stream().mapToInt(List::size).sum());
            appendHistory(history);
        } catch (Exception e) {
            log.error("Error loading history", e);
            saveToFile();
        }
    }

    public static void saveToFile() {
        try {
            String json = Common.prettyGson.toJson(history);
            Files.write(historyPath, json.getBytes());
        } catch (IOException e) {
            log.error("Error saving history", e);
        }
    }

    public static void appendHistory(Map<String, List<StockHistory>> history) {
        for (var entry : history.entrySet()) {
            for (var stockHistory : entry.getValue()) {
                //log.info("Appending history: {} {} {}", entry.getKey(), stockHistory.cost, stockHistory.timestamp);
                add(entry.getKey(), stockHistory.cost, stockHistory.timestamp);
            }
        }
        log.info("Appended history: {}", history.values().stream().mapToInt(List::size).sum());
    }

}
