package arc.arc.board;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisManager;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public class BoardMessager implements ChannelListener {

    public final String channel;
    public final RedisManager redisManager;

    @Override
    public void consume(String channel, String message) {
        UUID uuid = UUID.fromString(message);
        Board.instance().loadBoardEntry(uuid);
    }

    public void sendUpdate(UUID uuid){
        redisManager.publish(channel, uuid.toString());
    }
}
