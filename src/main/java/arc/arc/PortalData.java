package arc.arc;

import arc.arc.hooks.HuskHomesHook;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PortalData {

    HuskHomesHook.MyTeleport myTeleport;
    Location location;
    Player player;
    @Builder.Default
    boolean isHusk = false;
}
