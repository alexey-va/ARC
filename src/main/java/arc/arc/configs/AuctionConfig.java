package arc.arc.configs;

import arc.arc.ARC;
import lombok.SneakyThrows;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public class AuctionConfig {

    public static boolean broadcastItems;
    private static YamlConfiguration config;
    private static File file;

    public static List<String> categories;
    public static long refreshRate;

    @SneakyThrows
    public static void load() {
        file = new File(ARC.plugin.getDataFolder() + File.separator + "auction.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            ARC.plugin.saveResource("auction.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        loadConfig();
    }

    private static void loadConfig() {
        categories = config.getStringList("discord-categories");
        refreshRate = config.getLong("refresh-rate", 20L*60);
        broadcastItems = config.getBoolean("broadcast-items", false);
    }

}
