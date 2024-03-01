package arc.arc.xserver.ranks;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RankData {

    String rankName;
    String playerName;
    UUID playerUuid;
    int priority;
    String server;

}
