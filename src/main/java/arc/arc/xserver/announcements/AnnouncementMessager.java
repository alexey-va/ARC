package arc.arc.xserver.announcements;

import arc.arc.ARC;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.util.Common;
import arc.arc.xserver.XMessage;
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

    @Override
    public void consume(String channel, String message, String server) {
        XMessage data = Common.gson.fromJson(message, XMessage.class);
        AnnounceManager.queue(data);
    }


    public void send(XMessage data) {
        CompletableFuture.supplyAsync(() -> Common.gson.toJson(data))
                .thenAccept(json -> ARC.redisManager.publish(channel, json));
    }
}
