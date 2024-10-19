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
    ActionType actionType;
    HuskHomesHook.HuskTeleport huskTeleport;
    Location location;
    String command;

    public enum ActionType {
        COMMAND, HUSK, TELEPORT
    }

}
