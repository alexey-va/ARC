package arc.arc.xserver.ranks;

import arc.arc.ARC;
import arc.arc.configs.MainConfig;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class RankMessager implements ChannelListener {

    private final RedisManager redisManager;
    private final String channel;
    Gson gson = new Gson();

    @Override
    public void consume(String channel, String message, String server) {
        if (server.equals(MainConfig.server)) return;
        RankData data = gson.fromJson(message, RankData.class);
        CMIUser user = CMI.getInstance().getPlayerManager().getUser(data.playerUuid);

        if (user == null) return;
        try {
            new BukkitRunnable() {
                @Override
                public void run() {
                    CMIRank rank = user.getRank();
                    int priority = Integer.parseInt(rank.getName().substring(1));
                    if (priority >= data.priority) return;
                    if (!rank.getName().equals(data.rankName)) {
                        CMIRank cmiRank = CMI.getInstance().getRankManager().getRank(data.rankName);
                        if (cmiRank == null) return;
                        user.setRank(cmiRank);
                        System.out.println("Setting user's rank: " + user.getName() + " to " + cmiRank.getName());
                    }
                }
            }.runTask(ARC.plugin);
        } catch (Exception ignored){}
    }

    public void send(RankData rankData) {
        CompletableFuture.supplyAsync(() -> RedisSerializer.toJson(rankData))
                        .thenAccept(json -> redisManager.publish(channel, json));
    }
}
