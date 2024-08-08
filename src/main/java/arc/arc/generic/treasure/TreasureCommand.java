package arc.arc.generic.treasure;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TreasureCommand implements Treasure{

    String command;
    @Builder.Default
    Map<String, Object> attributes = new HashMap<>();
    int weight;

    @Override
    public void give(Player player) {
        String s = PlaceholderAPI.setPlaceholders(player, command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), s);
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "command");
        map.put("command", command);
        map.put("weight", weight);
        map.put("attributes", attributes);
        return map;
    }

    @Override
    public int weight() {
        return weight;
    }
}
