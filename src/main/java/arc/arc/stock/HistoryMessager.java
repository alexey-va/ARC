package arc.arc.stock;

import arc.arc.ARC;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.util.Common;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class HistoryMessager implements ChannelListener {
    public final String channel;
    private final RedisManager redisManager;

    @Override
    public void consume(String channel, String message, String originServer) {
        if (ARC.serverName.equalsIgnoreCase(originServer)) return;
        try {
            TypeToken<Map<String, HistoryManager.HighLow>> typeToken = new TypeToken<>() {
            };
            Map<String, HistoryManager.HighLow> highLowMap = Common.gson.fromJson(message, typeToken.getType());
            HistoryManager.setHighLows(highLowMap);
        } catch (Exception e) {
            log.error("Error consuming highlows", e);
        }
    }

    public void send(Map<String, HistoryManager.HighLow> highLowMap) {
        try {
            String json = Common.gson.toJson(highLowMap);
            redisManager.publish(channel, json);
        } catch (Exception e) {
            log.error("Error sending highlows", e);
        }
    }
}
