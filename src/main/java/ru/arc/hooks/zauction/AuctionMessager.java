package ru.arc.hooks.zauction;

import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;
import ru.arc.util.Common;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
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
            log.error("Error sending auction items", e);
        }
    }
}
