package ru.arc.xserver;

import ru.arc.ARC;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

import static ru.arc.util.Logging.error;

@Slf4j
public class XActionManager {

    private static XActionMessager messager;


    public static void init() {
        messager = new XActionMessager();
        if(ARC.redisManager == null) {
            error("Redis manager is not initialized. XActionManager will not work.");
            return;
        }
        ARC.redisManager.registerChannelUnique(XActionMessager.CHANNEL, messager);
        ARC.redisManager.init();
    }

    public static void run(XAction action) {
        //info("Running action: {}", action);
        action.run();
    }

    public static void publish(XAction action) {
        //info("Publishing action: {}", action);
        messager.send(action);
    }


    public static void movePlayerToServer(Player player, String server) {
        ARC.pluginMessenger.sendPlayerToServer(player, server);
    }
}
