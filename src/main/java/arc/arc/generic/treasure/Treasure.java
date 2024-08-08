package arc.arc.generic.treasure;

import org.bukkit.entity.Player;

import java.util.Map;

public interface Treasure {
    void give(Player player);
    Map<String, Object> attributes();
    Map<String, Object> serialize();
    int weight();
}
