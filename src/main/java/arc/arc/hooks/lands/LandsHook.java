package arc.arc.hooks.lands;

import arc.arc.ARC;
import arc.arc.hooks.HookRegistry;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.LandWorld;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LandsHook {

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

    public boolean canBuild(Player player, Chunk chunk){
        LandPlayer landPlayer =  integration.getLandPlayer(player.getUniqueId());
        LandWorld landWorld = integration.getWorld(chunk.getWorld());
        if(landWorld == null || landPlayer == null) return false;
        Location location = chunk.getBlock(1,1,1).getLocation();
        return landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_BREAK,Material.STONE,false) &&
                landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_PLACE, Material.STONE, false);
    }
}
