package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.Config;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import redis.clients.jedis.JedisPooled;

import java.util.UUID;

public class LandsHook implements ArcModule {

    LandsIntegration integration;

    public LandsHook() {
        integration = LandsIntegration.of(ARC.plugin);
    }

    public void sendSpawnLocation(UUID uuid, JedisPooled pooled) {
        integration.getOfflineLandPlayer(uuid).thenAccept(offlinePlayer -> {
            Location location = null;
            Land land = offlinePlayer.getEditLand();
            if (land != null) location = land.getSpawn();

            String response;
            if (location == null) response = uuid + ";;;" + "NULL";
            else
                response = uuid + ";;;" + location.getWorld().getName() + ";;;" + location.getX() + ";;;" + location.getY() + ";;;" + location.getZ() +
                        ";;;" + location.getYaw() + ";;;" + location.getPitch();

            pooled.publish("arc.lands_response", (response + ":::" + Config.server));
        });
    }

    public void tpSpawn(Player player) {
        LandPlayer landPlayer = integration.getLandPlayer(player.getUniqueId());
        if(landPlayer == null) return;
        Land land = landPlayer.getEditLand();
        if(land == null) return;
        Location location = land.getSpawn();
        if(location == null) return;
        if(ARC.plugin.huskHomesHook != null) ARC.plugin.huskHomesHook.createPortal(player, location);
        else player.teleport(location);
    }
}
