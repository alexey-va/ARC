package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import arc.arc.util.Common;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;

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
        AnnouncementData data = Common.gson.fromJson(message, AnnouncementData.class);
        if (Objects.equals(server, MainConfig.server)) return;
        if (data == null || MainConfig.server.equalsIgnoreCase(data.originServer)) return;
        if (!data.everywhere && !data.servers.contains(MainConfig.server)) return;
        data = announcementDataCache.getOrDefault(data, data);
        AnnouncementData finalData = data;
        Bukkit.getScheduler().runTask(ARC.plugin, () -> AnnounceManager.announceLocally(finalData));
    }


    public void send(AnnouncementData data) {
        CompletableFuture.supplyAsync(() -> Common.gson.toJson(data))
                .thenAccept(json -> manager.publish(channel, json));
    }
}
