package arc.arc.hooks.zauction;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class AuctionMessager implements ChannelListener {
    public final String channel, channelAll;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {

    }

    public void send(List<AuctionItemDto> itemDtoList){
        try {
            redisManager.publish(channel, new ObjectMapper().writeValueAsString(itemDtoList));
        } catch (Exception e){
            e.printStackTrace();
        }
    }
}
