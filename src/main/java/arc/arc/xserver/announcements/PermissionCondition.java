package arc.arc.xserver.announcements;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bukkit.entity.Player;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PermissionCondition extends ArcCondition {

    String permission;

    @Override
    public boolean test(Player player) {
        return player.hasPermission(permission);
    }
}
