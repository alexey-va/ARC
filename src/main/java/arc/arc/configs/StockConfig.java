package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.stock.HistoryManager;
import arc.arc.stock.StockClient;
import arc.arc.stock.StockMarket;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class StockConfig {

    public static String mainMenuBackCommand;
    public static long stockRefreshRate;
    public static double maxBuyPrice;
    public static double maxLeveragedPrice;
    public static long historyLifetime;
    public static long dividendPeriod;
    public static double dividendPercentFromPrice;


    private static YamlConfiguration config;
    private static File file;

    private static File cacheFile;

    private static File historyFile;


    public static boolean mainServer;
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

        historyFile = new File(ARC.plugin.getDataFolder() + File.separator + "stocks/history.json");
        if (!historyFile.exists()) {
            historyFile.getParentFile().mkdirs();
            historyFile.createNewFile();
        }

        loadConfig();
        loadStockHistory();
    }



    private static void loadConfig() {
        mainServer = config.getBoolean("main-server", false);
        commission = config.getDouble("commission", 0.01);
        leveragePower = config.getDouble("leverage-power", 0.5);
        mainMenuBackCommand = config.getString("main-menu-back-command", "menu");
        stockRefreshRate = config.getLong("stock-refresh-rate", 300);
        defaultStockMaxAmount = config.getInt("default-max-stock-amount", 10);
        maxBuyPrice = config.getDouble("max-buy-price", 1_000_000);
        maxLeveragedPrice = config.getDouble("max-leveraged-price", 10_000_000);
        historyLifetime = config.getLong("history-lifetime", 60*60*24*3L);
        dividendPeriod = config.getLong("dividend-period", 60*60*4L);
        dividendPercentFromPrice = config.getDouble("dividend-percent-from-price", 0.02);
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

        String FINN_API_KEY = config.getString("finn-api-key");
        String POLY_API_KEY = config.getString("poly-api-key");

        StockMarket.setClient(new StockClient(FINN_API_KEY, POLY_API_KEY));
    }

    public static void saveStockHistory() {
        HistoryManager.saveToFile(historyFile);
    }

    public static void loadStockHistory() {
        HistoryManager.loadFromFile(historyFile);
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
