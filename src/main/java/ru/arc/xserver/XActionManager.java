package ru.arc.xserver;

import org.bukkit.entity.Player;
import ru.arc.ARC;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;
import static ru.arc.util.Logging.withContext;

public class XActionManager {

    private static XActionMessager messager;


    public static void init() {
        messager = new XActionMessager();
        if(ARC.redisManager == null) {
            error("Redis manager is not initialized. XActionManager will not work.");
            return;
        }
        ARC.redisManager.registerChannelUnique(XActionMessager.CHANNEL, messager);
        // init() is called once globally after all modules register their channels
        info("XActionManager registered channel: {}", XActionMessager.CHANNEL);
    }

    public static void run(XAction action) {
        withContext("xaction", null, "run", () -> {
            info("[XAction] Running action on this server: {}", action);
            action.run();
        });
    }

    public static void publish(XAction action) {
        if (messager == null) {
            error("[XAction] Cannot publish — messager is null (Redis not initialized?)");
            return;
        }
        withContext("xaction", null, "publish", () -> {
            info("[XAction] Publishing action: {}", action);
            messager.send(action);
        });
    }


    public static void movePlayerToServer(Player player, String server) {
        ARC.pluginMessenger.sendPlayerToServer(player, server);
    }
}
