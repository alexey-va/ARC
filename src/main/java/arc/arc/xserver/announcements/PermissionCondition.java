package arc.arc.xserver.announcements;

import lombok.*;
import org.bukkit.entity.Player;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PermissionCondition implements ArcCondition {

    String permission;

    @Override
    public boolean test(Player player) {
        if (permission.startsWith("!")) return !player.hasPermission(permission.substring(1));
        return player.hasPermission(permission);
    }
}
