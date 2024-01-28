package arc.arc.playerlist;

import arc.arc.network.ChannelListener;
import arc.arc.network.RedisSerializer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlayerListListener implements ChannelListener {

    @Getter
    private final String channel;

    @Override
    public void consume(String channel, String message) {
        PlayerList playerList = RedisSerializer.fromJson(message, PlayerList.class);
        if(playerList == null){
            System.out.println("Message "+message+" canot be parsed!");
            return;
        }
        PlayerManager.refreshServerPlayers(playerList.server, playerList.getPlayerList());
    }
}
