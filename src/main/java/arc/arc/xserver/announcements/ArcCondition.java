package arc.arc.xserver.announcements;

import arc.arc.network.ArcSerializable;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.bukkit.entity.Player;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = PermissionCondition.class, name = "permission")
})
public abstract class ArcCondition extends ArcSerializable {

    public abstract boolean test(Player player);

}
