package arc.arc.xserver;

import arc.arc.ARC;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

@Slf4j
public class PluginMessenger implements PluginMessageListener {

    public PluginMessenger() {
        Bukkit.getServer().getMessenger().registerOutgoingPluginChannel(ARC.plugin, "BungeeCord");
    }

    @Override
    public void onPluginMessageReceived(@NotNull String s, @NotNull Player player, @NotNull byte[] bytes) {

    }

    public void sendBungeeCord(Player player, byte[] bytes) {
        player.sendPluginMessage(ARC.plugin, "BungeeCord", bytes);
    }

    public void sendPlayerToServer(Player player, String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (Exception e) {
            log.error("Error in sendPlayerToServer", e);
            return;
        }
        sendBungeeCord(player, bytes.toByteArray());
    }
}
