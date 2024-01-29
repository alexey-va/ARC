package arc.arc;

import arc.arc.xserver.announcements.ArcCondition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AnnouneConfig {

    File file;
    YamlConfiguration configuration;

    int delay;

    public AnnouneConfig(){
        init();
    }

    private void init(){
        file = new File(ARC.plugin.getDataFolder() + File.separator + "announce.yml");
        if(!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        configuration = YamlConfiguration.loadConfiguration(file);
    }

    private void loadConfig(){
        delay = configuration.getInt("config.delay", 10);

        List<Map<String, Object>> messages = (List<Map<String, Object>>) configuration.getList("messages");
        for(var map : messages){
            String message = (String) map.get("message");
            if(message == null){
                System.out.println("Message is broken for "+map);
                continue;
            }

            List<String> servers = Arrays.stream(((String) map.get("servers")).split(",")).toList();

            boolean miniMessage = Boolean.parseBoolean((String) map.get("mini-message"));

            boolean playerSpecific = Boolean.parseBoolean((String) map.get("player-specific"));

            List<ArcCondition> conditions = new ArrayList<>();
            List<Map<String, Object>> conditionData = (List<Map<String, Object>>) map.get("conditions");

            for(var condMap : conditionData){
                ArcCondition condition;
                String type = (String) condMap.get("type");
                switch (type){
                    case "permission"-> {
                        condition = new
                    }
                }
            }

        }
    }

}
