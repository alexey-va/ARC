package ru.arc.xserver.announcements;

import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.util.Common;
import ru.arc.xserver.XMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
