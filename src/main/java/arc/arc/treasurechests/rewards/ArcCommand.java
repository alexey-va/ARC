package arc.arc.treasurechests.rewards;

import lombok.AllArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

@AllArgsConstructor
public class ArcCommand implements Treasure{

    String command;
    boolean rare;
    String rareMessage;
    String message;
    int weight;

    @Override
    public void give(Player player) {
        String s = PlaceholderAPI.setPlaceholders(player, command);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), s);
    }

    @Override
    public boolean isRare() {
        return rare;
    }

    @Override
    public String globalMessage() {
        return rareMessage;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "command");
        map.put("command", command);
        map.put("weight", weight);
        if(rare) map.put("rare", true);
        if(rareMessage != null) map.put("rare-message", rareMessage);
        return map;
    }

    @Override
    public int weight() {
        return weight;
    }
}
