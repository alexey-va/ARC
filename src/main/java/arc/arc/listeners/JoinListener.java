package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.configs.MainConfig;
import arc.arc.network.NetworkRegistry;
import arc.arc.sync.SyncManager;
import arc.arc.xserver.playerlist.PlayerManager;
import arc.arc.xserver.ranks.RankData;
import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinListener implements Listener {

    Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "join.yml");

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        SyncManager.playerJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerLeave(PlayerQuitEvent event) {
        SyncManager.playerQuit(event.getPlayer().getUniqueId());
        shareRank(event.getPlayer());
        invulnerable(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        SyncManager.playerQuit(event.getPlayer().getUniqueId());
    }


    private void shareRank(Player player) {
        if (player == null || !player.isOnline()) return;
        CMIUser user = CMI.getInstance().getPlayerManager().getUser(player.getUniqueId());
        try {
            CMIRank rank = user.getRank();
            String rankName = rank.getName();
            int priority = Integer.parseInt(rankName.substring(1));

            RankData rankData = new RankData(rank.getName(), player.getName(), player.getUniqueId(), priority, MainConfig.server);
            NetworkRegistry.rankMessager.send(rankData);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void invulnerable(Player player) {
        if (!config.bool("invulnerable.on-join", false)) return;
        if (player == null || !player.isOnline()) return;
        player.setInvulnerable(true);

        int defaultTicks = config.integer("invulnerable.ticks", 20 * 5);
        int resourcepackTicks = config.integer("invulnerable.resourcepack-ticks", 20 * 15);
        PlayerManager.PlayerData playerData = PlayerManager.getPlayerData(player.getUniqueId());
        boolean withResourcepack = player.hasPermission("mcfine.apply-rp")
                && Math.abs(playerData.getJoinTime() - System.currentTimeMillis()) < 1000;

        Bukkit.getScheduler().runTaskLater(ARC.plugin, () -> {
            if (!player.isOnline()) return;
            if (player.hasPermission("arc.bypass-invulnerable")) return;
            player.setInvulnerable(false);
        }, withResourcepack ? resourcepackTicks : defaultTicks);

    }

}
