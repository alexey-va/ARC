package arc.arc.generic.treasure;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.analysis.function.Gaussian;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
@Slf4j
public class TreasureItem implements Treasure {

    ItemStack stack;
    int quantity;
    GaussData gaussData;
    int weight;
    @Builder.Default
    Map<String, Object> attributes = new HashMap<>();


    @Override
    public void give(Player player) {
        List<ItemStack> stacks = generateStacks();
        player.getInventory()
                .addItem(stacks.toArray(ItemStack[]::new))
                .values()
                .forEach(st -> player.getWorld().dropItem(player.getLocation(), st));
    }

    public List<ItemStack> generateStacks() {
        int quant = generateAmountInt();
        List<ItemStack> stacks = new ArrayList<>();
        while (quant > 0) {
            int amount = Math.min(quant, 64);
            quant -= amount;
            stacks.add(stack.asQuantity(amount));
        }
        return stacks;
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }

    public double generateAmount() {
        if (gaussData == null) {
            return quantity;
        } else {
            if (gaussData.mean == null || gaussData.stdDev == null) {
                log.error("Gauss data is missing mean or stdDev");
                return quantity;
            }
            double v = ThreadLocalRandom.current().nextGaussian(gaussData.mean, gaussData.stdDev);
            if (gaussData.min != null && v < gaussData.min) v = gaussData.min;
            if (gaussData.max != null && v > gaussData.max) v = gaussData.max;
            return v;
        }
    }

    public int generateAmountInt() {
        return (int) Math.round(generateAmount());
    }


    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "item");
        map.put("quantity", quantity);
        if (gaussData != null) map.put("gaussData", gaussData.serialize());
        map.put("weight", weight);
        map.put("attributes", attributes());
        map.put("stack", stack.serialize());
        return map;
    }

    @Override
    public int weight() {
        return weight;
    }
}
