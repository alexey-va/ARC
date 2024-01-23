package arc.arc.hooks.lands;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.hooks.ArcModule;
import arc.arc.hooks.HookRegistry;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import redis.clients.jedis.JedisPooled;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LandsHook implements ArcModule {

    LandsIntegration integration;

    public LandsHook() {
        integration = LandsIntegration.of(ARC.plugin);
    }

    public CompletableFuture<Location> getSpawnLocation(UUID playerUuid) {
        return integration.getOfflineLandPlayer(playerUuid)
                .thenApply(offlinePlayer -> offlinePlayer.getEditLand().getSpawn())
                .exceptionally(e -> null);
    }

    public void tpSpawn(Player player) {
        LandPlayer landPlayer = integration.getLandPlayer(player.getUniqueId());
        if(landPlayer == null) return;
        Land land = landPlayer.getEditLand();
        if(land == null) return;
        Location location = land.getSpawn();
        if(location == null) return;
        if(HookRegistry.huskHomesHook != null) HookRegistry.huskHomesHook.createPortal(player, location);
        else player.teleport(location);
    }
}
