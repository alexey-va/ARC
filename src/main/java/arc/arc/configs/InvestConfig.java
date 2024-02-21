package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.invest.items.NamedItem;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InvestConfig {

    private static YamlConfiguration config;
    private static File file;

    public static void load(){
        File file = new File(ARC.plugin.getDataFolder()+File.separator+"investing"+File.separator+"invest.yml");
        if(file.getParentFile().exists()) file.getParentFile().mkdir();
        if(!file.exists()) ARC.plugin.saveResource("invest.yml", false);
        config = YamlConfiguration.loadConfiguration(file);

        NamedItem.load();
    }

    public static double number(String key){
        if(!config.contains(key)){
            //System.out.println("Locale does not contain key: "+key);
            inject(key, 1);
            return 1;
        }
        return config.getDouble(key);
    }

    public static List<String> stringList(String key){
        if(!config.contains(key)){
            //System.out.println("Locale does not contain list key: "+key);
            inject(key, new ArrayList<String>());
            return new ArrayList<>();
        }
        if(config.isString(key)) return List.of(config.getString(key, key));
        return config.getStringList(key);
    }

    public static String string(String key){
        if(!config.contains(key)){
            //System.out.println("Locale does not contain key: "+key);
            inject(key, key);
            return key;
        }
        return config.getString(key);
    }

    private static void inject(String key, Object o){
        config.set(key, o);
        try {
            config.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
