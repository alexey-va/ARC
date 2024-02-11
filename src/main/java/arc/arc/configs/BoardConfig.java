package arc.arc.configs;

import arc.arc.ARC;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BoardConfig {

    public static List<String> lore;
    public static String display;
    public static String descriptionPrefix;
    public static List<String> editBottom;
    public static List<String> rateBottom;
    public static String mainMenuBackCommand;
    public static int shortNameLength;
    public static double publishCost;
    public static double editCost;
    public static String createEntryGuiName;
    public static String editEntryGuiName;
    public static String rateGuiName;
    public static String boardGuiName;
    public static boolean mainServer;
    public static int secondsLifetime;
    public static int secondsAnnounce;
    public static String receivePermission;

    private static YamlConfiguration config;

    public BoardConfig(){
        loadConfig();
    }

    public void loadConfig(){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"board.yml");
        if(!file.exists()) ARC.plugin.saveResource("board.yml", false);
        config = YamlConfiguration.loadConfiguration(file);

        display = config.getString("item.display", "KEK");
        lore = config.getStringList("item.lore");
        descriptionPrefix = config.getString("item.description-prefix");
        editBottom = config.getStringList("item.click-to-edit");
        rateBottom = config.getStringList("item.click-to-rate");
        mainMenuBackCommand = config.getString("main-menu-back-command", "menu");
        shortNameLength = config.getInt("short-name-length", 20);
        publishCost = config.getDouble("publish-cost", 25000);
        editCost = config.getDouble("edit-cost", 1000);
        secondsLifetime = config.getInt("entry-lifetime-seconds", 86400);
        mainServer = config.getBoolean("main-server", false);
        secondsAnnounce = config.getInt("seconds-announce", 600);
        receivePermission = config.getString("receive-permission", "arc.board-announce");


        createEntryGuiName = config.getString("create-entry-gui-name", "&7Создать объявление");
        createEntryGuiName = LegacyComponentSerializer.legacyAmpersand()
                .serialize(MiniMessage.miniMessage().deserialize(createEntryGuiName));


        editEntryGuiName = config.getString("edit-entry-gui-name", "&7Редактировать объявление");
        editEntryGuiName = LegacyComponentSerializer.legacyAmpersand()
                .serialize(MiniMessage.miniMessage().deserialize(editEntryGuiName));


        boardGuiName = config.getString("board-gui-name", "&7Доска объявлений");
        boardGuiName = LegacyComponentSerializer.legacyAmpersand()
                .serialize(MiniMessage.miniMessage().deserialize(boardGuiName));

        rateGuiName = config.getString("rate-gui-name", "&7Оценить объявление");
        rateGuiName = LegacyComponentSerializer.legacyAmpersand()
                .serialize(MiniMessage.miniMessage().deserialize(rateGuiName));
    }

    public static List<String> getStringList(String key){
        if(!config.contains(key)){
            System.out.println("Locale does not contain list key: "+key);
            return new ArrayList<>();
        }
        if(config.isString(key)) return List.of(config.getString(key, key));
        return config.getStringList(key);
    }

    public static String getString(String key){
        if(!config.contains(key)){
            System.out.println("Locale does not contain key: "+key);
            return key;
        }
        return config.getString(key);
    }

}
