package arc.arc.hooks.zauction;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.util.Common;
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
