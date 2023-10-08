package arc.arc;

import java.util.*;

public class PlayerManager {

    private static Map<String, Map<String, PlayerData>> playerMap = new HashMap<>();

    public static Set<String> getPlayerNames() {
        return playerMap.keySet();
    }

    public static Map<String, PlayerData> getPlayerMap(String server) {
        return playerMap.get(server);
    }

    public static List<String> getAllPlayerNames() {
        List<String> players = new ArrayList<>();
        playerMap.forEach((k, v) -> {
            v.forEach((k1, v1) -> {
                players.add(k1);
            });
        });
        return players;
    }

    public static List<UUID> getAllPlayerUuids() {
        List<UUID> players = new ArrayList<>();
        playerMap.forEach((k, v) -> {
            v.forEach((k1, v1) -> {
                players.add(v1.uuid);
            });
        });
        return players;
    }

    public static void addPlayer(String name, UUID uuid, String server) {
        if (!playerMap.containsKey(server)) playerMap.put(server, new HashMap<>());
        playerMap.get(server).put(name, new PlayerData(name, server, uuid));
    }

    public static void addPlayers(String server, Map<String, UUID> data) {
        if (!playerMap.containsKey(server)) playerMap.put(server, new HashMap<>());
        playerMap.get(server).clear();
        data.forEach((k, v) -> {
            removePlayer(k);
            playerMap.get(server).put(k, new PlayerData(k, server, v));
        });
    }

    public PlayerData getPlayerData(String name) {
        for (var entry : playerMap.entrySet())
            if (entry.getValue().containsKey(name)) return entry.getValue().get(name);
        return null;
    }

    public PlayerData getPlayerData(UUID uuid) {
        for (var entry : playerMap.entrySet()) {
            for (var entry2 : entry.getValue().entrySet()) {
                if (entry2.getValue().uuid.equals(uuid)) return entry2.getValue();
            }
        }
        return null;
    }

    public static void removePlayer(String name) {
        for (var map : playerMap.values()) {
            map.remove(name);
        }
    }

    record PlayerData(String name, String server, UUID uuid) {
    }

}
