package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.board.ItemIcon;
import arc.arc.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class StockConfig {

    public static String mainMenuBackCommand;
    public static long stockRefreshRate;



    private static YamlConfiguration config;
    private static File file;

    private static YamlConfiguration cacheConfig;
    private static File cacheFile;

    private static File historyFile;


    public static boolean mainServer;
    public static int refreshRate;
    public static double commission;
    public static double leveragePower;
    public static int defaultStockMaxAmount;
    public static Location stockMarketLocation;
    public static double updateImagesRadius;

    public static List<Material> iconMaterials;
    public static TreeMap<Integer, String> permissionMap = new TreeMap<>();

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
        commission = config.getDouble("commission", 0.01);
        leveragePower = config.getDouble("leverage-power", 0.5);
        mainMenuBackCommand = config.getString("main-menu-back-command", "menu");
        stockRefreshRate = config.getLong("stock-refresh-rate", 5L);
        defaultStockMaxAmount = config.getInt("default-max-stock-amount", 10);
        String sml = config.getString("stock-market-location", null);
        if(sml != null){
            String[] strings = sml.split(",");
            double x = Double.parseDouble(strings[0]);
            double y = Double.parseDouble(strings[1]);
            double z = Double.parseDouble(strings[2]);
            String worldName =  strings[3];
            World world = Bukkit.getWorld(worldName);
            if(world == null){
                System.out.println("Could not find world: "+worldName);
            } else{
                stockMarketLocation = new Location(world, x,y,z);
            }
        }
        updateImagesRadius = config.getDouble("update-images-radius", 50);
        iconMaterials = config.getStringList("icon-materials").stream()
                .map(String::toUpperCase)
                .map(Material::matchMaterial)
                .collect(Collectors.toList());
        if(iconMaterials.isEmpty()) iconMaterials.add(Material.PAPER);

        for(String s : config.getStringList("max-stock-permissions")){
            try {
                String permission = s.split(":")[0];
                int amount = Integer.parseInt(s.split(":")[1]);
                if(permissionMap.containsKey(amount)){
                    System.out.println("Permission map already has amount: "+amount);
                    continue;
                }
                permissionMap.put(amount, permission);
            } catch (Exception e){
                e.printStackTrace();
                System.out.println("Could not parse "+s);
            }
        }

        for (var map : config.getMapList("stocks")) {
            try {
                StockMarket.loadStockFromMap(map);
            } catch (Exception e) {
                System.out.println("Error parsing " + map);
                e.printStackTrace();
            }
        }

        for (var map : config.getMapList("currencies")) {
            try {
                StockMarket.loadCurrencyFromMap(map);
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


}
