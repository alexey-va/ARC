package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class AnnouncementMessager implements ChannelListener {

    @Getter
    final String channel;
    final RedisManager manager;

    Map<AnnouncementData, AnnouncementData> announcementDataCache = new HashMap<>();

    @Override
    public void consume(String channel, String message, String server) {
        AnnouncementData data = RedisSerializer.fromJson(message, AnnouncementData.class);
        if(Objects.equals(server, Config.server)) return;
        if(data == null || Config.server.equalsIgnoreCase(data.originServer)) return;
        if(!data.everywhere && !data.servers.contains(Config.server)) return;
        data = announcementDataCache.getOrDefault(data, data);
        AnnouncementData finalData = data;
        Bukkit.getScheduler().runTask(ARC.plugin, () -> AnnounceManager.announceLocally(finalData));
    }


    public void send(AnnouncementData data){
        CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(data))
                .thenAccept(json -> manager.publish(channel, json));
    }
}
