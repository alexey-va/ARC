package arc.arc.generic.treasure;

import arc.arc.hooks.HookRegistry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TreasureCommand implements Treasure {

    String command;
    @Builder.Default
    Map<String, Object> attributes = new HashMap<>();
    int weight;

    @Override
    public void give(Player player) {
        String s = HookRegistry.papiHook == null ? command :
                HookRegistry.papiHook.parse(command, player);
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
