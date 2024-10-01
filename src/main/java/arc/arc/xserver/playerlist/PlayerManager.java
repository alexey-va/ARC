package arc.arc.xserver.playerlist;

import arc.arc.ARC;
import com.google.gson.Gson;
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
import java.util.stream.Collectors;

@Log4j2
public class PlayerManager {


    private static final Map<UUID, PlayerData> playerMap = new ConcurrentHashMap<>();
    private static Gson gson = new Gson();

    public static List<Player> getOnlinePlayersThreadSafe() {
        if (Bukkit.isPrimaryThread()) return new ArrayList<>(Bukkit.getOnlinePlayers());
        CompletableFuture<List<Player>> res = new CompletableFuture<>();
        new BukkitRunnable() {
            @Override
            public void run() {
                res.complete(new ArrayList<>(Bukkit.getOnlinePlayers()))
            }
        }.runTask(ARC.plugin);
        try {
            return res.get(3, TimeUnit.MINUTE);
        } catch (Exception e) {
            log.error("Timeout waiting for players", e);
            return List.of();
        }
    }

    public static Set<String> getPlayerNames() {
        return playerMap.values().stream().map(PlayerData::getUsername).collect(Collectors.toSet());
    }

    public static List<UUID> getAllPlayerUuids() {
        return new ArrayList<>(playerMap.keySet());
    }

    public static void readMessage(String json) {
        Type type = new TypeToken<List<PlayerData>>() {
        }.getType();
        List<PlayerData> playerData = gson.fromJson(json, type);
        if (playerData == null) {
            log.error("Message " + json + " canot be parsed!");
            return;
        }
        playerMap.clear();
        Map<UUID, PlayerData> newMap = new HashMap<>();
        for (PlayerData data : playerData) newMap.put(data.getUuid(), data);
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
