package arc.arc.xserver;

import arc.arc.ARC;
import arc.arc.network.ChannelListener;
import arc.arc.util.Common;

import java.util.concurrent.CompletableFuture;

public class XActionMessager implements ChannelListener {

    public static final String CHANNEL = "arc.xactions";

    @Override
    public void consume(String channel, String message, String originServer) {
        XAction action = Common.gson.fromJson(message, XAction.class);
        XActionManager.run(action);
    }

    public void send(XAction action) {
        CompletableFuture.supplyAsync(() -> Common.gson.toJson(action))
                .thenAccept(str -> ARC.redisManager.publish(CHANNEL, str));
    }
}
