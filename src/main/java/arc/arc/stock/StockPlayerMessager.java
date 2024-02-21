package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class StockPlayerMessager implements ChannelListener {

    @Getter
    private final String channel;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {
        if (originServer.equalsIgnoreCase(Config.server)) return;
        StockPlayerManager.loadStockPlayer(message);
    }

    public void send(String uuid){
        redisManager.publish(channel, uuid);
    }
}
