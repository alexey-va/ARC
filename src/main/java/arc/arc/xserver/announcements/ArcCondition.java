package arc.arc.xserver.announcements;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.bukkit.entity.Player;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = PermissionCondition.class, name = "permission"),
        @JsonSubTypes.Type(value = PlayerCondition.class, name = "player")
})
public abstract interface ArcCondition {

    boolean test(Player player);

}
