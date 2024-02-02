package arc.arc.treasurechests.rewards;

import org.bukkit.entity.Player;

import java.util.Map;

public interface Treasure {

    void give(Player player);

    boolean isRare();

    String globalMessage();
    String message();

    Map<String, Object> serialize();
    int weight();

}
