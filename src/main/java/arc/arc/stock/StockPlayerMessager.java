package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class StockPlayerMessager implements ChannelListener {

    @Getter
    private final String channel;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {
        if (originServer.equalsIgnoreCase(Config.server)) return;
        StockPlayerManager.loadStockPlayer(UUID.fromString(message));
    }

    public StockPlayer loadStockPlayer(UUID uuid){
        try {
            return redisManager.loadMapEntries("arc.stock_players", uuid.toString())
                    .thenApply(list -> {
                        if (list == null || list.isEmpty()) return null;
                        String json = list.get(0);
                        try {
                            StockPlayer stockPlayer = new ObjectMapper().readValue(json, StockPlayer.class);
                            stockPlayer.setDirty(false);
                            return stockPlayer;
                            //System.out.println("Loaded player " + stockPlayer.playerName + " data!");
                        } catch (JsonProcessingException e) {
                            System.out.println("Could not load data2 of " + uuid+": "+json);
                            throw new RuntimeException(e);
                        }
                    }).get();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public Map<UUID, StockPlayer> loadAllStockPlayers(){
        try {
            return redisManager.loadMap("arc.stock_players")
                    .thenApply(map -> {
                        Map<UUID, StockPlayer> result = new ConcurrentHashMap<>();
                        for (var entry : map.entrySet()) {
                            try {
                                StockPlayer stockPlayer = new ObjectMapper().readValue(entry.getValue(), StockPlayer.class);
                                result.put(stockPlayer.playerUuid, stockPlayer);
                                //System.out.println("Loaded stock data of " + stockPlayer.playerName + ": " + stockPlayer);
                            } catch (JsonProcessingException e) {
                                System.out.println("Could not load stock data of " + entry.getKey());
                                e.printStackTrace();
                            }
                        }
                        return result;
                    }).get();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void saveStockPlayer(StockPlayer stockPlayer){
        if (!stockPlayer.isDirty()) return;
        stockPlayer.setDirty(false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return new ObjectMapper().writeValueAsString(stockPlayer);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(json -> {
            ARC.redisManager.saveMapEntries("arc.stock_players", stockPlayer.playerUuid.toString(), json);
            new BukkitRunnable() {
                @Override
                public void run() {
                    send(stockPlayer.playerUuid.toString());
                }
            }.runTaskLaterAsynchronously(ARC.plugin, 5L);
        });
    }

    public void send(String uuid){
        redisManager.publish(channel, uuid);
    }
}
