package arc.arc.xserver.ranks;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import arc.arc.network.RedisSerializer;
import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Containers.CMIUser;
import com.Zrips.CMI.Modules.Ranks.CMIRank;
import lombok.RequiredArgsConstructor;
import org.bukkit.scheduler.BukkitRunnable;

@RequiredArgsConstructor
public class RankMessager implements ChannelListener {

    private final RedisManager redisManager;
    private final String channel;

    @Override
    public void consume(String channel, String message) {
        RankData data = RedisSerializer.fromJson(message, RankData.class);
        //System.out.println(data);
        if (data.server.equals(Config.server)) return;
        CMIUser user = CMI.getInstance().getPlayerManager().getUser(data.playerUuid);
        //System.out.println(user);
        if (user == null) return;
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
                }
            }
        }.runTask(ARC.plugin);

    }

    public void send(RankData rankData) {
        redisManager.publish(channel, RedisSerializer.toJson(rankData));
    }
}
