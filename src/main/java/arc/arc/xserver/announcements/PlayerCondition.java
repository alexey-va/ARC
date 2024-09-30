package arc.arc.xserver.announcements;

import lombok.*;
import org.bukkit.entity.Player;

import java.util.UUID;

@AllArgsConstructor
@Getter @Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class PlayerCondition extends ArcCondition{
    UUID uuid;
    @Override
    public boolean test(Player player) {
        return player.getUniqueId().equals(uuid);
    }
}
