package arc.arc.hooks.lands;

import arc.arc.network.ServerLocation;
import lombok.*;

import java.util.UUID;

@Getter @Setter @ToString
@NoArgsConstructor
@AllArgsConstructor
public class LandsRequest {

    UUID uuid;
    UUID playerUuid;
    ServerLocation serverLocation = null;


}
