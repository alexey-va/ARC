package arc.arc.hooks.lands;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import arc.arc.network.ServerLocation;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class LandsMessager implements ChannelListener {

    private final RedisManager redisManager;

    @Getter
    private final String reqChannel;

    @Getter
    private final String  respChannel;
    private static BukkitTask cleanupTask;
    Map<UUID, TimedRequest> futures = new ConcurrentHashMap<>();


    public void init(){
        if(cleanupTask != null && cleanupTask.isCancelled()) cleanupTask.cancel();
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                futures.entrySet()
                        .removeIf(e -> Duration.between(e.getValue().instant(), Instant.now()).getSeconds() > 5);
            }
        }.runTaskTimerAsynchronously(ARC.plugin, 20L, 20L);
    }


    @Override
    public void consume(String channel, String message, String server) {
        if(channel.equals(respChannel)) {
            if (futures.isEmpty()) return;
            LandsRequest landsRequest = RedisSerializer.fromJson(message, LandsRequest.class);
            if (landsRequest == null) return;
            TimedRequest request = futures.get(landsRequest.uuid);
            request.future.complete(landsRequest.serverLocation);
        } else if(channel.equals(reqChannel)){
            if(HookRegistry.landsHook == null) return;
            LandsRequest landsRequest = RedisSerializer.fromJson(message, LandsRequest.class);
            if(landsRequest == null || landsRequest.playerUuid == null) return;
            HookRegistry.landsHook.getSpawnLocation(landsRequest.playerUuid)
                    .thenApply(ServerLocation::of)
                    .thenApply(loc -> new LandsRequest(landsRequest.uuid, landsRequest.playerUuid, loc))
                    .thenApply(RedisSerializer::toJson)
                    .thenAccept(json -> redisManager.publish(respChannel, json));
        }
    }


    public CompletableFuture<ServerLocation> getSpawnLocation(UUID playerUuid){
        UUID uuid = UUID.randomUUID();
        LandsRequest landsRequest = new LandsRequest(uuid, playerUuid, null);
        CompletableFuture<ServerLocation> future = new CompletableFuture<>();
        futures.put(uuid, new TimedRequest(future, Instant.now()));
        redisManager.publish(reqChannel, RedisSerializer.toJson(landsRequest));
        return future;
    }

    record TimedRequest(CompletableFuture<ServerLocation> future, Instant instant){}
}
