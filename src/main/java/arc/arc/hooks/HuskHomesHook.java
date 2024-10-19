package arc.arc.hooks;

import arc.arc.Portal;
import arc.arc.PortalData;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.event.TeleportWarmupEvent;
import net.william278.huskhomes.teleport.TimedTeleport;
import net.william278.huskhomes.user.BukkitUser;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import static arc.arc.PortalData.ActionType.HUSK;

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

    public void teleport(HuskTeleport teleport) {
        HuskHomesAPI.getInstance().teleportBuilder((OnlineUser) teleport.teleport.getTeleporter())
                .target(teleport.teleport.getTarget()).toTeleport().execute();
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
