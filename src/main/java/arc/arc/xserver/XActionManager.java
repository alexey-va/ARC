package arc.arc.xserver;

import arc.arc.ARC;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;

@Slf4j
public class XActionManager {

    private static XActionMessager messager;


    public static void init() {
        messager = new XActionMessager();
        ARC.redisManager.registerChannelUnique(XActionMessager.CHANNEL, messager);
        ARC.redisManager.init();
    }

    public static void run(XAction action) {
        log.info("Running action: {}", action);
        action.run();
    }

    public static void publish(XAction action) {
        log.info("Publishing action: {}", action);
        messager.send(action);
    }


    public static void movePlayerToServer(Player player, String server) {
        ARC.pluginMessenger.sendPlayerToServer(player, server);
    }
}
