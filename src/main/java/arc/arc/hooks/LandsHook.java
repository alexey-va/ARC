package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.network.entries.CrossServerLocation;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LandsHook implements ArcModule {

    LandsIntegration integration;

    public LandsHook() {
        integration = LandsIntegration.of(ARC.plugin);
    }

    public CompletableFuture<CrossServerLocation> getSpawnLocation(UUID uuid) {
        integration.getOfflineLandPlayer(uuid).thenApply(offlinePlayer -> {
            Location location = null;
            Land land = offlinePlayer.getEditLand();
            if (land != null) location = land.getSpawn();
            if (location == null) return null;
            return new CrossServerLocation(
                    Config.server, location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()
            );
        });
    }

    public void tpSpawn(Player player) {
        LandPlayer landPlayer = integration.getLandPlayer(player.getUniqueId());
        if (landPlayer == null) return;
        Land land = landPlayer.getEditLand();
        if (land == null) return;
        Location location = land.getSpawn();
        if (location == null) return;
        if (ARC.plugin.huskHomesHook != null) ARC.plugin.huskHomesHook.createPortal(player, location);
        else player.teleport(location);
    }
}
