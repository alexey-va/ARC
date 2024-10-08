package arc.arc.hooks;

import arc.arc.Portal;
import arc.arc.PortalData;
import arc.arc.configs.MainConfig;
import arc.arc.network.ServerLocation;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.event.TeleportWarmupEvent;
import net.william278.huskhomes.position.Position;
import net.william278.huskhomes.position.World;
import net.william278.huskhomes.teleport.Teleport;
import net.william278.huskhomes.teleport.TeleportationException;
import net.william278.huskhomes.teleport.TimedTeleport;
import net.william278.huskhomes.user.BukkitUser;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class HuskHomesHook implements Listener {

    @EventHandler
    public void husk(TeleportWarmupEvent event) {
        event.setCancelled(true);
        double cost = 0;
        boolean res = true;
        Player player = null;
        try {
            player = ((BukkitUser) ((OnlineUser) event.getTimedTeleport().getTeleporter())).getPlayer();
            res = (((OnlineUser) event.getTimedTeleport().getTeleporter()).hasPermission("huskhomes.bypass_economy_checks"));
        } catch (Exception ignored) {
        }
        if (event.getTimedTeleport().getType() == Teleport.Type.BACK && !res) cost = 0;
        //else if(event.getTimedTeleport().getType() == Teleport.Type.TELEPORT) cost = 1000;
        if (event.getTimedTeleport().getTeleporter() instanceof OnlineUser user) {
            new Portal(user.getUuid().toString(), PortalData.builder()
                    .player(player)
                    .isHusk(true)
                    .myTeleport(new MyTeleport(event.getTimedTeleport()))
                    .build(), 0);
        }

    }

    public void teleport(Player player, String server, double x, double y, double z, float yaw, float pitch, String world) {
        Position position = Position.at(x, y, z, yaw, pitch, World.from(world, UUID.randomUUID()), server);

        try {
            OnlineUser onlineUser = HuskHomesAPI.getInstance().adaptUser(player);
            TimedTeleport teleport = HuskHomesAPI.getInstance().teleportBuilder()
                    .teleporter(onlineUser)
                    .target(position)
                    .toTimedTeleport();

            new Portal(onlineUser.getUuid().toString(), PortalData.builder()
                    .isHusk(true)
                    .myTeleport(new MyTeleport(teleport))
                    .player(player)
                    .build());
        } catch (TeleportationException e) {
            e.printStackTrace();
        }
    }

    public void teleport(Player player, ServerLocation serverLocation) {
        Position position = Position.at(serverLocation.getX(),
                serverLocation.getY(), serverLocation.getZ(), serverLocation.getYaw(), serverLocation.getPitch(),
                World.from(serverLocation.getWorld(), UUID.randomUUID()), serverLocation.getServer());

        try {
            OnlineUser onlineUser = HuskHomesAPI.getInstance().adaptUser(player);
            TimedTeleport teleport = HuskHomesAPI.getInstance().teleportBuilder()
                    .teleporter(onlineUser)
                    .target(position)
                    .toTimedTeleport();

            new Portal(onlineUser.getUuid().toString(), PortalData.builder()
                    .isHusk(true)
                    .myTeleport(new MyTeleport(teleport))
                    .player(player)
                    .build());
        } catch (TeleportationException e) {
            e.printStackTrace();
        }
    }

    public void teleport(MyTeleport teleport) {
        HuskHomesAPI.getInstance().teleportBuilder((OnlineUser) teleport.teleport.getTeleporter())
                .target(teleport.teleport.getTarget()).toTeleport().execute();
    }

    public void createPortal(Player player, Location location) {
        Position position = Position.at(HuskHomesAPI.getInstance().adaptLocation(location), MainConfig.server);

        try {
            OnlineUser onlineUser = HuskHomesAPI.getInstance().adaptUser(player);
            TimedTeleport teleport = HuskHomesAPI.getInstance().teleportBuilder()
                    .teleporter(onlineUser)
                    .target(position)
                    .toTimedTeleport();

            new Portal(onlineUser.getUuid().toString(), PortalData.builder()
                    .isHusk(true)
                    .myTeleport(new MyTeleport(teleport))
                    .player(player)
                    .build());
        } catch (TeleportationException e) {
            e.printStackTrace();
        }
    }

    public static class MyTeleport {
        TimedTeleport teleport;

        public MyTeleport(TimedTeleport teleport) {
            this.teleport = teleport;
        }

        public OfflinePlayer getPlayer() {
            return Bukkit.getOfflinePlayer(((OnlineUser) teleport.getTeleporter()).getUuid());
        }
    }

}
