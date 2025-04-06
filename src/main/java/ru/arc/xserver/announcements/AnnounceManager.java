package ru.arc.xserver.announcements;

import ru.arc.ARC;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.xserver.XActionManager;
import ru.arc.xserver.XCondition;
import ru.arc.xserver.XMessage;
import ru.arc.xserver.playerlist.PlayerManager;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
public class AnnounceManager {


    static Deque<XMessage> recentlyUsed = new ArrayDeque<>(2);
    static BukkitTask task;
    static BukkitTask messageTask;
    static int totalWeight;
    static Config config = ConfigManager.of(ARC.plugin.getDataPath(), "announce.yml");

    private static final TreeMap<Integer, XMessage> announcements = new TreeMap<>();
    private static final Deque<XMessage> queue = new ConcurrentLinkedDeque<>();

    public static void init() {
        try {
            cancel();

            announcements.clear();
            totalWeight = 0;
            loadXMessages();

            messageTask = new BukkitRunnable() {
                @Override
                public void run() {
                    while (!queue.isEmpty()) {
                        XMessage data = queue.poll();
                        for (Player player : PlayerManager.getOnlinePlayersThreadSafe()) {
                            List<XCondition> conditions = data.getConditions();
                            boolean fits = true;
                            if (conditions != null) {
                                for (XCondition condition : conditions) {
                                    if (!condition.test(player)) {
                                        fits = false;
                                        break;
                                    }
                                }
                            }
                            if (fits) send(data, player);
                        }
                    }
                }
            }.runTaskTimer(ARC.plugin, 0, 1L);

            task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (announcements.isEmpty()) return;
                    XMessage data = getRandom();
                    announce(data);
                }
            }.runTaskTimer(ARC.plugin,
                    config.integer("config.delay-seconds", 600) * 20L,
                    config.integer("config.delay-seconds", 600) * 20L);
        } catch (Exception e) {
            log.error("Error initializing AnnounceManager: {}", e.getMessage());
        }
    }

    private static void loadXMessages() {
        List<String> messages = config.keys("messages");
        for (var key : messages) {
            var builder = XMessage.builder();

            String message = config.string("messages." + key + ".message");
            XMessage.Type type = XMessage.Type.valueOf(config.string("messages." + key + ".type", "chat").toUpperCase());

            XMessage.SerializationType serializationType;
            try {
                serializationType = XMessage.SerializationType.valueOf(config.string("messages." + key + ".serialization-type", "mini_message").toUpperCase());
            } catch (Exception e) {
                log.error("Serialization type not found for message: {}", key);
                serializationType = XMessage.SerializationType.MINI_MESSAGE;
            }

            int weight = config.integer("messages." + key + ".weight", 1);

            if (config.exists("messages." + key + ".toast")) {
                String toastTitle = config.string("messages." + key + ".toast.title");
                Material toastMaterial = config.material("messages." + key + ".toast.material", Material.STONE);
                int toastModelData = config.integer("messages." + key + ".toast.model-data", 0);
                builder.toastData(XMessage.ToastData.builder()
                        .title(toastTitle)
                        .material(toastMaterial)
                        .modelData(toastModelData)
                        .build());
            }

            if (config.exists("messages." + key + ".bossbar")) {
                String bossBarName = config.string("messages." + key + ".bossbar.name");
                BarColor barColor = BarColor.valueOf(config.string("messages." + key + ".bossbar.color", "red").toUpperCase());
                int bossBarSeconds = config.integer("messages." + key + ".bossbar.seconds", 5);
                builder.bossBarData(XMessage.BossBarData.builder()
                        .name(bossBarName)
                        .color(barColor)
                        .seconds(bossBarSeconds)
                        .build());
            }

            Set<String> servers = new HashSet<>(config.stringList("messages." + key + ".servers", List.of("all")));
            List<XCondition> xConditions = new ArrayList<>();

            if (!servers.contains("all")) {
                for (var server : servers) {
                    xConditions.add(XCondition.ofServerName(server));
                }
            }

            if (config.exists("messages." + key + ".conditions")) {
                List<Map<String, Object>> conditions = config.list("messages." + key + ".conditions");
                for (var map : conditions) {
                    String condType = (String) map.get("type");
                    switch (condType) {
                        case "permission" -> xConditions.add(XCondition.ofPermission((String) map.get("permission")));
                        case "player" ->
                                xConditions.add(XCondition.ofPlayerUuid(UUID.fromString((String) map.get("uuid"))));
                    }
                }
            }
            builder.conditions(xConditions)
                    .serializedMessage(message)
                    .type(type)
                    .serializationType(serializationType)
                    .announceData(XMessage.AnnounceData.builder()
                            .weight(weight)
                            .build());

            addAnnouncement(builder.build());
        }
    }


    public static void cancel() {
        if (task != null && !task.isCancelled()) task.cancel();
        if (messageTask != null && !messageTask.isCancelled()) messageTask.cancel();
    }


    public static void sendMessageGlobally(UUID playerUuid, String mmString) {
        announce(XMessage.builder()
                .serializedMessage(mmString)
                .type(XMessage.Type.CHAT)
                .serializationType(XMessage.SerializationType.MINI_MESSAGE)
                .conditions(List.of(XCondition.ofPlayerUuid(playerUuid)))
                .build());
    }

    public static void announce(XMessage data) {
        log.info("Announcing message: {}", data);
        XActionManager.publish(data);
    }

    private static void send(XMessage data, Player player) {
        switch (data.getType()) {
            case CHAT -> player.sendMessage(data.component(player));
            case BOSS_BAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.error("I cant use bossbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendBossbar(
                        "arcAnnounce",
                        data.getSerializedMessage(),
                        player,
                        data.getBossBarData().getColor(),
                        data.getBossBarData().getSeconds(),
                        data.getBossBarData().getKeepFor()
                );
            }
            case ACTION_BAR -> {
                if (HookRegistry.cmiHook == null) {
                    log.error("I cant use actionbar without cmi... sorry");
                    return;
                }
                HookRegistry.cmiHook.sendActionbar(
                        data.getSerializedMessage(),
                        List.of(player),
                        data.getActionBarData().getSeconds()
                );
            }
        }
    }

    public static void addAnnouncement(XMessage data) {
        totalWeight += Optional.ofNullable(data.getAnnounceData()).map(XMessage.AnnounceData::getWeight).orElse(1);
        announcements.put(totalWeight, data);
    }

    private static XMessage getRandom() {
        int rng = new Random().nextInt(0, totalWeight + 1);
        XMessage data = announcements.ceilingEntry(rng).getValue();
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

    public static void queue(XMessage data) {
        queue.offer(data);
    }
}
