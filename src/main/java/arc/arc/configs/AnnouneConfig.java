package arc.arc.configs;

import arc.arc.ARC;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.announcements.AnnouncementData;
import arc.arc.xserver.announcements.ArcCondition;
import arc.arc.xserver.announcements.PermissionCondition;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class AnnouneConfig {

    File file;
    YamlConfiguration configuration;

    public static int delay;

    public AnnouneConfig(){
        init();
    }

    private void init(){
        Path path = Paths.get(ARC.plugin.getDataFolder() + File.separator + "announce.yml");
        file = path.toFile();
        if(!file.exists()) {
            ARC.plugin.saveResource("announce.yml", false);
        }

        configuration = YamlConfiguration.loadConfiguration(file);
        delay = configuration.getInt("config.delay", 20);
        AnnounceManager.instance().clearData();
        loadConfig();
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

            List<String> servers = Arrays.stream(((String) map.getOrDefault("servers", "all")).split(",")).toList();

            boolean miniMessage = (Boolean) map.getOrDefault("mini-message", true);
            int weight = (Integer) map.getOrDefault("weight", 1);

            boolean playerSpecific = (Boolean) map.getOrDefault("player-specific", false);
            boolean cache = (Boolean) map.getOrDefault("cache", true);
            boolean everywhere = servers.stream().anyMatch(s -> s.equalsIgnoreCase("all"));

            AnnouncementData.Type annType = AnnouncementData.Type
                    .valueOf(((String) map.getOrDefault("type", "chat")).toUpperCase());

            String color = (String) map.getOrDefault("color", "red");
            int seconds = (Integer) map.getOrDefault("seconds", 5);

            List<ArcCondition> conditions = new ArrayList<>();
            List<Map<String, Object>> conditionData = (List<Map<String, Object>>) map.getOrDefault("conditions", new ArrayList<>());

            for(var condMap : conditionData){
                ArcCondition condition = null;
                String type = (String) condMap.get("type");
                switch (type){
                    case "permission"-> {
                        condition = new PermissionCondition((String) condMap.get("permission"));
                    }
                }
                if(condition != null) conditions.add(condition);
            }

            AnnouncementData data = AnnouncementData.builder()
                    .arcConditions(conditions)
                    .cache(cache)
                    .playerSpecific(playerSpecific)
                    .message(message)
                    .servers(servers)
                    .everywhere(everywhere)
                    .minimessage(miniMessage)
                    .weight(weight)
                    .type(annType)
                    .bossBarColor(color)
                    .seconds(seconds)
                    .originServer(MainConfig.server)
                    .build();

            //System.out.println("Loaded message: "+data);
            AnnounceManager.instance().addAnnouncement(data);
        }
    }

}
