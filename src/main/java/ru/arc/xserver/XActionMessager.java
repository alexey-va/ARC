package ru.arc.xserver;

import ru.arc.ARC;
import ru.arc.network.ChannelListener;
import ru.arc.util.Common;

import java.util.concurrent.CompletableFuture;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;

public class XActionMessager implements ChannelListener {

    public static final String CHANNEL = "arc.xactions";

    @Override
    public void consume(String channel, String message, String originServer) {
        info("[XAction] Received message on channel '{}' from server '{}': {}", channel, originServer, message);
        XAction action;
        try {
            action = Common.gson.fromJson(message, XAction.class);
        } catch (Exception e) {
            error("[XAction] Failed to deserialize action from server '{}': {}", originServer, message, e);
            return;
        }
        if (action == null) {
            error("[XAction] Deserialized null action from server '{}': {}", originServer, message);
            return;
        }
        info("[XAction] Deserialized action type={} from server '{}'", action.getClass().getSimpleName(), originServer);
        XActionManager.run(action);
    }

    public void send(XAction action) {
        CompletableFuture.supplyAsync(() -> {
            String json = Common.gson.toJson(action);
            info("[XAction] Serialized for publish: {}", json);
            return json;
        }).thenAccept(str -> {
            if (ARC.redisManager == null) {
                error("[XAction] Cannot publish — redisManager is null");
                return;
            }
            ARC.redisManager.publish(CHANNEL, str);
            info("[XAction] Published to channel '{}'", CHANNEL);
        }).exceptionally(e -> {
            error("[XAction] Exception during publish", e);
            return null;
        });
    }
}
