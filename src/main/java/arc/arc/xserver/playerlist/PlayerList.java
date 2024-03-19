package arc.arc.xserver.playerlist;

import arc.arc.configs.MainConfig;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class PlayerList  {

    List<PlayerData> playerList = new ArrayList<>();
    String server = MainConfig.server;
    Instant lastUpdated = Instant.now();

    public PlayerList(String server, List<PlayerData> collect) {
        playerList = collect;
    }

    public void addPlayer(PlayerData data) {
        playerList.add(data);
    }


}
