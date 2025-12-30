package ru.arc.hooks.zauction;

import java.util.List;

import lombok.RequiredArgsConstructor;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;
import ru.arc.util.Common;

import static ru.arc.util.Logging.error;

@RequiredArgsConstructor
public class AuctionMessager implements ChannelListener {
    public final String channel, channelAll;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {

    }

    public void send(List<AuctionItemDto> itemDtoList) {
        try {
            redisManager.publish(channel, Common.gson.toJson(itemDtoList));
        } catch (Exception e) {
            error("Error sending auction items", e);
        }
    }
}
