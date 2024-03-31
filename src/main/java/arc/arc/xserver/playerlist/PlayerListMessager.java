package arc.arc.xserver.playerlist;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisSerializer;
import io.github.thebusybiscuit.slimefun4.libraries.dough.common.PlayerList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public record PlayerListMessager(String channel) implements ChannelListener {

    @Override
    public void consume(String channel, String message, String server) {
        PlayerManager.readMessage(message);
    }

}
