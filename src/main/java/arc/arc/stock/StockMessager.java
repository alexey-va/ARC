package arc.arc.stock;

import arc.arc.configs.Config;
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
        if(originServer.equals(Config.server)) return;
        Map<String, Map> stocks = RedisSerializer.fromJson(message, Map.class);
        Map<String, Stock> map = new HashMap<>();
        for(var entry : stocks.entrySet()){
            try {
                Stock stock = Stock.deserialize(entry.getValue());
                map.put(stock.symbol, stock);
            } catch (Exception e){
                e.printStackTrace();
                System.out.println(entry);
            }
        }
        StockMarket.setMap(map);
    }

    public void send(Map<String, Stock> stocks){
        try {
            Map<String, Map<String, Object>> serialized = new HashMap<>();
            for(var entry : stocks.entrySet()){
                serialized.put(entry.getKey(), entry.getValue().serialize());
            }
            String json = new ObjectMapper().writeValueAsString(serialized);
            redisManager.publish(channel, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
