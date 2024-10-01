package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.configs.MainConfig;
import arc.arc.hooks.HookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

@Slf4j
public class AnnounceManager {


    static TreeMap<Integer, AnnouncementData> announcements = new TreeMap<>();
    static Deque<AnnouncementData> recentlyUsed = new ArrayDeque<>(2);
    static BukkitTask task;
    static int totalWeight;
    static Config config;
    public static AnnouncementMessager messager;

    private static AnnounceManager instance;

    public static void init() {
        cancel();
        announcements.clear();
        totalWeight = 0;
        config = ConfigManager.of(ARC.plugin.getDataPath(), "announce.yml");
        loadConfig();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                announceNext();
            }
        }.runTaskTimer(ARC.plugin,
                config.integer("config.delay", 600) * 20L,
                config.integer("config.delay", 600) * 20L);
    }

    private static void loadConfig() {
        List<Map<String, Object>> messages = config.list("messages");
        for (var map : messages) {
            String message = (String) map.get("message");
            if (message == null) {
                log.error("Message is broken for {}", map);
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

            String colorStr = (String) map.getOrDefault("color", "red");
            BarColor color = BarColor.valueOf(colorStr.toUpperCase());
            int seconds = (Integer) map.getOrDefault("seconds", 5);

            List<ArcCondition> conditions = new ArrayList<>();
            List<Map<String, Object>> conditionData = (List<Map<String, Object>>) map.getOrDefault("conditions", new ArrayList<>());

            for (var condMap : conditionData) {
                ArcCondition condition = null;
                String type = (String) condMap.get("type");
                switch (type) {
                    case "permission" -> condition = new PermissionCondition((String) condMap.get("permission"));
                    case "player" -> condition = new PlayerCondition(UUID.fromString((String) condMap.get("uuid")));
                }
                if (condition != null) conditions.add(condition);
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
            addAnnouncement(data);
        }
    }

    public static void cancel() {
        if (task != null && !task.isCancelled()) task.cancel();
    }


    public static void sendMessage(UUID playerUuid, String mmString) {
        announceGlobally(new AnnouncementData.AnnouncementDataBuilder()
                .message(mmString)
                .arcConditions(List.of(new PlayerCondition(playerUuid)))
                .build());
    }


    public static void announceNext() {
        if (announcements.isEmpty()) return;
        AnnouncementData data = getRandom();
        announceGlobally(data);
    }

    public static void announceGlobally(AnnouncementData data) {
        Bukkit.getOnlinePlayers().forEach(p -> {
            for (ArcCondition condition : data.arcConditions) {
                if (!condition.test(p)) return;
            }
            sendMessage(data, p);
        });
        if (messager != null) messager.send(data);
    }

    public static void announceLocally(AnnouncementData data) {
        Bukkit.getOnlinePlayers().forEach(p -> {
            for (ArcCondition condition : data.arcConditions) {
                if (!condition.test(p)) return;
            }
            sendMessage(data, p);
        });
    }

    private static void sendMessage(AnnouncementData data, Player player) {
        switch (data.type) {
            case CHAT -> player.sendMessage(data.component(player));
            case BOSSBAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.error("I cant use bossbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendBossbar("arcAnnounce", data.message,
                        player, data.bossBarColor, data.seconds);
            }
            case ACTIONBAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.error("I cant use actionbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendActionbar(data.message,
                        List.of(player), data.seconds);
            }
        }
    }

    public static void addAnnouncement(AnnouncementData data) {
        totalWeight += data.weight;
        announcements.put(totalWeight, data);
    }

    private static AnnouncementData getRandom() {
        int rng = new Random().nextInt(0, totalWeight + 1);
        AnnouncementData data = announcements.ceilingEntry(rng).getValue();
        int count = 0;
        while (recentlyUsed.contains(data)) {
            if (count == 10) break;
            count++;
            rng = new Random().nextInt(0, totalWeight + 1);
            data = announcements.ceilingEntry(rng).getValue();
        }

        recentlyUsed.offerFirst(data);
        if (recentlyUsed.size() > queueSize()) recentlyUsed.pollLast();

        return data;
    }

    private static int queueSize() {
        return Math.min(announcements.size() - 1, 3);
    }

}
