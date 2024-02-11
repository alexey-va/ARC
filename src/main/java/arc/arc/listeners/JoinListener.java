package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.NetworkRegistry;
import arc.arc.xserver.ranks.RankData;
import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class JoinListener implements Listener {

    private static BukkitTask shareRankTask;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event){
/*        new BukkitRunnable() {
            @Override
            public void run() {
                shareRank(event.getPlayer());
            }
        }.runTaskLater(ARC.plugin, 20L);*/
    }

    public JoinListener(){
        shareRankTask = new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.getOnlinePlayers().forEach(JoinListener.this::shareRank);
            }
        }.runTaskTimer(ARC.plugin, 20L, 20L);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        shareRank(event.getPlayer());
    }


    private void shareRank(Player player){
        if(player == null || !player.isOnline()) return;
        CMIUser user = CMI.getInstance().getPlayerManager().getUser(player.getUniqueId());
        try{
            CMIRank rank = user.getRank();
            String rankName = rank.getName();
            int priority = Integer.parseInt(rankName.substring(1));

            RankData rankData = new RankData(rank.getName(), player.getName(), player.getUniqueId(), priority, Config.server);
            NetworkRegistry.rankMessager.send(rankData);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

}
