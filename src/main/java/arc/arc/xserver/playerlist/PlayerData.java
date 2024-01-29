package arc.arc.xserver.playerlist;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerData {

    String username, server;
    UUID uuid;

}
