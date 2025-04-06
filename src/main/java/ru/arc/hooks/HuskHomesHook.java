package ru.arc.hooks;

import ru.arc.Portal;
import ru.arc.PortalData;
import lombok.extern.slf4j.Slf4j;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.event.HomeCreateEvent;
import net.william278.huskhomes.event.TeleportWarmupEvent;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.teleport.TimedTeleport;
import net.william278.huskhomes.user.BukkitUser;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static ru.arc.PortalData.ActionType.HUSK;

@Slf4j
public class HuskHomesHook implements Listener {

    @EventHandler
    public void husk(TeleportWarmupEvent event) {
        event.setCancelled(true);
        if (event.getTimedTeleport().getTeleporter() instanceof BukkitUser user) {
            new Portal(user.getUuid(), PortalData.builder()
                    .actionType(HUSK)
                    .huskTeleport(new HuskTeleport(event.getTimedTeleport()))
                    .build());
        }

    }

    @EventHandler
    public void homeCreate(HomeCreateEvent event) {
        if(!event.getName().endsWith("home")) return;
        if(event.getCreator() instanceof BukkitUser user) {
            Player player = user.getPlayer();
            Position p = event.getPosition();
            World world = Bukkit.getWorld(p.getWorld().getName());
            Location location = new Location(world, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
            log.info("Setting respawn location for {} {}", player.getName(), location);
            player.setRespawnLocation(location, true);
        }
    }

    public void teleport(HuskTeleport teleport, Player player) {
        OnlineUser onlineUser = HuskHomesAPI.getInstance().adaptUser(player);
        HuskHomesAPI.getInstance().teleportBuilder(onlineUser)
                .target(teleport.teleport.getTarget()).toTeleport().execute();
    }

    public CompletableFuture<Boolean> hasHome(Player player) {
        OnlineUser user = HuskHomesAPI.getInstance().adaptUser(player);
        return HuskHomesAPI.getInstance().getUserHomes(user)
                .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(List::isEmpty)
                .thenApply(isEmpty -> !isEmpty);
    }

    public void createDefaultHome(Player player, @NotNull Location location) {
        try {
            log.info("Creating default home for player {} at {}", player.getName(), location);
            OnlineUser user = HuskHomesAPI.getInstance().adaptUser(player);
            HuskHomesAPI.getInstance().createHome(user, "home", HuskHomesAPI.getInstance().adaptPosition(location));
        } catch (Exception e) {
            log.error("Error creating default home for player {}", player.getName(), e);
        }
    }

    public static class HuskTeleport {
        TimedTeleport teleport;

        public HuskTeleport(TimedTeleport teleport) {
            this.teleport = teleport;
        }

        public OfflinePlayer getPlayer() {
            return Bukkit.getOfflinePlayer(((OnlineUser) teleport.getTeleporter()).getUuid());
        }
    }

}
