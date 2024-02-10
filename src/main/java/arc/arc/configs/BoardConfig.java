package arc.arc.configs;

import arc.arc.ARC;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public class BoardConfig {

    public static List<String> lore;
    public static String display;
    public static String descriptionPrefix;
    public static List<String> editBottom;
    public static List<String> rateBottom;
    public static String mainMenuBackCommand;
    public static int shortNameLength;
    public static int secondsLifetime;

    private static YamlConfiguration config;

    public BoardConfig(){
        loadConfig();
    }

    public void loadConfig(){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"buildings.yml");
        config = YamlConfiguration.loadConfiguration(file);
        if(!file.exists()) ARC.plugin.saveResource("buildings.yml", false);

        display = config.getString("item.display", "KEK");
        lore = config.getStringList("item.lore");
        descriptionPrefix = config.getString("item.description-prefix");
        editBottom = config.getStringList("click-to-edit");
        rateBottom = config.getStringList("click-to-rate");
        mainMenuBackCommand = config.getString("main-menu-back-command");
        shortNameLength = config.getInt("short-name-length", 20);
    }

    public static List<String> getStringList(String key){
        if(config.isString(key)) return List.of(config.getString(key, key));
        return config.getStringList(key);
    }

    public static String getString(String key){
        return config.getString(key);
    }

}
