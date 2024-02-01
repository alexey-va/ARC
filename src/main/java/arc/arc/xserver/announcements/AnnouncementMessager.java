package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class AnnouncementMessager implements ChannelListener {

    @Getter
    final String channel;
    final RedisManager manager;

    Map<AnnouncementData, AnnouncementData> announcementDataCache = new HashMap<>();

    @Override
    public void consume(String channel, String message) {
        AnnouncementData data = RedisSerializer.fromJson(message, AnnouncementData.class);
        if(data == null || data.originServer.equalsIgnoreCase(Config.server)) return;
        if(!data.everywhere && !data.servers.contains(Config.server)) return;
        data = announcementDataCache.getOrDefault(data, data);
        AnnouncementData finalData = data;
        new BukkitRunnable() {
            @Override
            public void run() {
                AnnounceManager.announce(finalData);
            }
        }.runTask(ARC.plugin);

    }


    public void send(AnnouncementData data){
        CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(data))
                .thenAccept(json -> manager.publish(channel, json));
    }
}
