package arc.arc.treasurechests.rewards;

import lombok.AllArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
public class ArcItem implements Treasure {

    ItemStack stack;
    int quantity;
    boolean rare;
    String rareMessage;
    String message;
    int weight;


    @Override
    public void give(Player player) {
        int quant = quantity;
        List<ItemStack> stacks = new ArrayList<>();
        while (quant > 0) {
            int amount = Math.min(quant, 64);
            quant -= amount;
            stacks.add(stack.asQuantity(amount));
        }
        player.getInventory()
                .addItem(stacks.toArray(ItemStack[]::new))
                .values()
                .forEach(st -> player.getWorld().dropItem(player.getLocation(), st));
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
        map.put("type", "item");
        map.put("quantity", quantity);
        map.put("weight", weight);
        if(rare) map.put("rare", true);
        if(rareMessage != null) map.put("rare-message", rareMessage);
        map.put("stack", stack.serialize());
        return map;
    }

    @Override
    public int weight() {
        return weight;
    }
}
