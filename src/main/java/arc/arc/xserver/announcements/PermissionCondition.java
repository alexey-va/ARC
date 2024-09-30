package arc.arc.xserver.announcements;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;

@Slf4j
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class PermissionCondition extends ArcCondition {

    String permission;

    @Override
    public boolean test(Player player) {
        log.info("Testing permission condition: {} for {}", permission, player.getName());
        if (permission.startsWith("!")) return !player.hasPermission(permission.substring(1));
        return player.hasPermission(permission);
    }
}
