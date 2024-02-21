package arc.arc.stock;

import arc.arc.configs.StockConfig;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class StockMessager implements ChannelListener {

    @Getter
    private final String channel;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {
        if(StockConfig.mainServer) return;
        Map<String, Map> stocks = RedisSerializer.fromJson(message, Map.class);
        Map<String, Stock> map = new HashMap<>();
        for(var entry : stocks.entrySet()){
            Stock stock = Stock.deserialize(entry.getValue());
            map.put(stock.symbol, stock);
        }
        StockMarket.setMap(map);
    }

    public void send(Map<String, Stock> stocks){
        try {
            String json = new ObjectMapper().writeValueAsString(stocks);
            redisManager.publish(channel, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
