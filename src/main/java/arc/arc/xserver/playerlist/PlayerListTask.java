package arc.arc.xserver.playerlist;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class PlayerListTask {

    private static BukkitTask task;
    RedisManager redisManager;

    public PlayerListTask(RedisManager redisManager){
        this.redisManager = redisManager;
    }

    public void init(){
        if(task != null && !task.isCancelled()) task.cancel();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                PlayerList list = getPlayerList();
                CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(list))
                                .thenAccept(pl -> redisManager.publish("arc.player_list", pl));
            }
        }.runTaskTimer(ARC.plugin, 20L, 100L);
    }

    private static PlayerList getPlayerList(){
        return new PlayerList(
                Config.server,
                Bukkit.getOnlinePlayers().stream()
                .map(p -> new PlayerData(p.getName(), Config.server, p.getUniqueId()))
                .collect(Collectors.toList())
        );
    }

}