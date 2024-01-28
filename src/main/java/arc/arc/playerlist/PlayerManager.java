package arc.arc.playerlist;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlayerManager {


    private static final Map<String, PlayerData> playerMap = new ConcurrentHashMap<>();
    public static Set<String> getPlayerNames() {
        return playerMap.keySet();
    }


    public static List<UUID> getAllPlayerUuids() {
        return playerMap.values().stream().map(PlayerData::getUuid).collect(Collectors.toList());
    }

    public static void addPlayer(PlayerData data) {
        playerMap.put(data.getUsername(), data);
    }

    public static void addPlayers(Collection<PlayerData> data) {
        data.forEach(PlayerManager::addPlayer);
    }

    public static void refreshServerPlayers(String server, Collection<PlayerData> data){
        trimServerPlayers(server);
        addPlayers(data);
    }

    public static void trimServerPlayers(String server){
        playerMap.entrySet().removeIf(e -> e.getValue().getServer().equals(server));
    }




}
