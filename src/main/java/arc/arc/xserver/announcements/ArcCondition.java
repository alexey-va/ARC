package arc.arc.xserver.announcements;

import arc.arc.network.adapters.JsonSubtype;
import arc.arc.network.adapters.JsonType;
import org.bukkit.entity.Player;

@JsonType(
        property = "type",
        subtypes = {
                @JsonSubtype(clazz = PermissionCondition.class, name = "permission"),
                @JsonSubtype(clazz = PlayerCondition.class, name = "player")
        }
)
public abstract class ArcCondition {

    abstract boolean test(Player player);
}
