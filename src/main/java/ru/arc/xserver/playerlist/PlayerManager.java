package ru.arc.xserver.playerlist;

import ru.arc.ARC;
import ru.arc.util.Common;
import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ru.arc.util.Logging.error;

@Log4j2
public class PlayerManager {


    private static final Map<UUID, PlayerData> playerMap = new ConcurrentHashMap<>();
    private static final Set<String> servers = new HashSet<>();

    public static List<Player> getOnlinePlayersThreadSafe() {
        if (Bukkit.isPrimaryThread()) return new ArrayList<>(Bukkit.getOnlinePlayers());
        CompletableFuture<List<Player>> res = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                res.complete(new ArrayList<>(Bukkit.getOnlinePlayers()));
            }
        }.runTask(ARC.plugin);
        try {
            return res.get(3, TimeUnit.MINUTES);
        } catch (Exception e) {
            error("Timeout waiting for players", e);
            return List.of();
        }
    }

    public static Set<String> getPlayerNames() {
        return playerMap.values().stream().map(PlayerData::getUsername).collect(Collectors.toSet());
    }

    public static Set<UUID> getPlayerUuids() {
        return playerMap.keySet();
    }

    public static Set<String> getServerNames() {
        return servers;
    }

    public static void readMessage(String json) {
        Type type = new TypeToken<List<PlayerData>>() {
        }.getType();
        List<PlayerData> playerData = Common.gson.fromJson(json, type);
        if (playerData == null) {
            error("Message {} canot be parsed!", json);
            return;
        }
        playerMap.clear();
        Map<UUID, PlayerData> newMap = new HashMap<>();
        for (PlayerData data : playerData) {
            servers.add(data.getServer());
            newMap.put(data.getUuid(), data);
        }
        playerMap.putAll(newMap);
    }

    public static PlayerData getPlayerData(UUID uniqueId) {
        return playerMap.get(uniqueId);
    }


    @Data
    @AllArgsConstructor
    public static class PlayerData {
        String username, server;
        UUID uuid;
        long joinTime;
    }


}
