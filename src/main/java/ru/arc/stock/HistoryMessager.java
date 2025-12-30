package ru.arc.stock;

import java.util.Map;

import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.network.RedisManager;
import ru.arc.util.Common;

import static ru.arc.util.Logging.error;

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
            error("Error consuming highlows", e);
        }
    }

    public void send(Map<String, HistoryManager.HighLow> highLowMap) {
        try {
            String json = Common.gson.toJson(highLowMap);
            redisManager.publish(channel, json);
        } catch (Exception e) {
            error("Error sending highlows", e);
        }
    }
}
