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
import org.jetbrains.annotations.NotNull;

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


    public boolean canBuild(Player player, Chunk chunk) {
        LandPlayer landPlayer = integration.getLandPlayer(player.getUniqueId());
        LandWorld landWorld = integration.getWorld(chunk.getWorld());
        if (landWorld == null) return true;
        if (landPlayer == null) return false;
        Location location = chunk.getBlock(1, 1, 1).getLocation();
        return landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_BREAK, Material.STONE, false) &&
                landWorld.hasRoleFlag(landPlayer, location, Flags.BLOCK_PLACE, Material.STONE, false);
    }

    public boolean isClaimed(@NotNull Location location) {
        LandWorld landWorld = integration.getWorld(location.getWorld());
        if (landWorld == null) return false;
        return landWorld.getArea(location) != null;
    }
}
