package arc.arc.stock;

import arc.arc.configs.MainConfig;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class HistoryMessager implements ChannelListener {
    public final String channel;
    private final RedisManager redisManager;
    @Override
    public void consume(String channel, String message, String originServer) {
        if(MainConfig.server.equalsIgnoreCase(originServer)) return;
        var om = new ObjectMapper();
        MapType mt = om.getTypeFactory()
                .constructMapType(ConcurrentHashMap.class, String.class, HistoryManager.HighLow.class);
        try {
            Map<String, HistoryManager.HighLow> highLowMap = om.readValue(message,mt);
            HistoryManager.setHighLows(highLowMap);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            System.out.println("Could not parse message: "+message);
        }
    }

    public void send(Map<String, HistoryManager.HighLow> highLowMap){
        try {
            String json = new ObjectMapper().writeValueAsString(highLowMap);
            redisManager.publish(channel, json);
        } catch (Exception e){
            e.printStackTrace();
            System.out.println("Could not serialize "+highLowMap+" with jackson!");
        }
    }
}
