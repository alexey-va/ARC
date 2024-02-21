package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.board.ItemIcon;
import arc.arc.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.SneakyThrows;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StockConfig {

    public static String mainMenuBackCommand;
    public static long stockRefreshRate;

    public record CurrencyData(String id, String display, List<String> lore, boolean crypto, ItemIcon icon) {
    }

    private static YamlConfiguration config;
    private static File file;

    private static YamlConfiguration cacheConfig;
    private static File cacheFile;

    private static File historyFile;


    public static boolean mainServer;
    public static int refreshRate;
    public static double commission;

    public static Map<String, CurrencyData> currencyDataMap = new HashMap<>();

    @SneakyThrows
    public static void load() {
        file = new File(ARC.plugin.getDataFolder() + File.separator + "stocks/stock.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            ARC.plugin.saveResource("stocks/stock.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        cacheFile = new File(ARC.plugin.getDataFolder() + File.separator + "stocks/cache.yml");
        if (!cacheFile.exists()) {
            cacheFile.getParentFile().mkdirs();
            cacheFile.createNewFile();
        }
        cacheConfig = YamlConfiguration.loadConfiguration(cacheFile);

        historyFile = new File(ARC.plugin.getDataFolder() + File.separator + "stocks/history.json");
        if (!historyFile.exists()) {
            historyFile.getParentFile().mkdirs();
            historyFile.createNewFile();
        }

        loadConfig();
        loadStockCache();
        loadStockHistory();
    }

    public static void saveStockCache(List<Map<String, Object>> list) {
        try {
            cacheConfig.load(cacheFile);
            cacheConfig.set("cache", list);
            cacheConfig.save(cacheFile);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static void loadStockCache() {
        var list = cacheConfig.getMapList("cache");
        list.forEach(StockMarket::loadCacheFromMap);
    }

    private static void loadConfig() {
        mainServer = config.getBoolean("main-server", false);
        refreshRate = config.getInt("refresh-rate", 60);
        commission = config.getDouble("commission", 0.03);
        mainMenuBackCommand = config.getString("main-menu-back-command", "menu");
        stockRefreshRate = config.getLong("stock-refresh-rate", 5L);

        for (var map : config.getMapList("stocks")) {
            try {
                StockMarket.loadFromMap(map, true);
            } catch (Exception e) {
                System.out.println("Fail loading stock: " + map);
                e.printStackTrace();
            }
        }

        for (var map : config.getMapList("currencies")) {
            try {
                CurrencyData currencyData = currencyData((Map<String, Object>) map);
                System.out.println("Parsed: "+currencyData);
                currencyDataMap.put(currencyData.id, currencyData);
            } catch (Exception e) {
                System.out.println("Error parsing " + map);
                e.printStackTrace();
            }
        }

        String FINN_API_KEY = config.getString("finn-api-key");
        String POLY_API_KEY = config.getString("poly-api-key");

        StockMarket.setClient(new StockClient(FINN_API_KEY, POLY_API_KEY));
    }

    @SneakyThrows
    public static void saveStockHistory(Map<String, List<StockMarket.StockHistory>> history) {
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(historyFile, history);
    }

    public static void loadStockHistory() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            TypeFactory typeFactory = objectMapper.getTypeFactory();
            MapType mapType = typeFactory.constructMapType(
                    ConcurrentHashMap.class,
                    typeFactory.constructType(String.class),
                    typeFactory.constructCollectionType(List.class, StockMarket.StockHistory.class)
            );

            Map<String, List<StockMarket.StockHistory>> history = objectMapper.readValue(historyFile, mapType);
            StockMarket.setHistory(history);
        } catch (Exception e) {
            saveStockHistory(new ConcurrentHashMap<>());
        }
    }

    public static String string(String key) {
        if (!config.contains("locale." + key)) {
            inject("locale." + key, key);
            return key;
        }

        return config.getString("locale." + key);
    }

    public static List<String> stringList(String key) {
        if (!config.contains("locale." + key)) {
            inject("locale." + key, List.of(key));
            return List.of(key);
        }

        return config.getStringList("locale." + key);
    }

    private static void inject(String key, Object value) {
        config.set(key, value);
        try {
            config.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    private static CurrencyData currencyData(Map<String, Object> map) {
        ItemIcon icon = ItemIcon.of(Material.PAPER, 0);
        if (map.containsKey("icon")) icon = new ObjectMapper().readValue((String) map.get("icon"), ItemIcon.class);
        return new CurrencyData(
                (String) map.get("id"),
                (String) map.getOrDefault("display", "display"),
                (List<String>) map.getOrDefault("lore", new ArrayList<String>()),
                (Boolean) map.getOrDefault("crypto", false),
                icon
        );
    }
}
