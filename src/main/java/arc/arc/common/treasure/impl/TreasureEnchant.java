package arc.arc.common.treasure.impl;

import arc.arc.common.treasure.GiveFlags;
import arc.arc.common.treasure.Treasure;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@Builder
public class TreasureEnchant extends Treasure {

    int quantity;
    Set<String> exclude;

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        List<Enchantment> list = Registry.ENCHANTMENT.stream()
                .filter(e -> !exclude.contains(e.key().asString().toLowerCase()))
                .toList();
        for (int i = 0; i < quantity; i++) {
            Enchantment e = list.get(ThreadLocalRandom.current().nextInt(list.size()));
            ItemStack item = ItemStack.of(Material.ENCHANTED_BOOK);
            item.addEnchantment(e, ThreadLocalRandom.current().nextInt(e.getMaxLevel()) + 1);
            player.getInventory().addItem(item).forEach((k, v) -> {
                Item item1 = player.getWorld().dropItem(player.getLocation(), v);
                item1.setOwner(player.getUniqueId());
            });
        }
    }


    @Override
    public Map<String, Object> serializeInternal() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("exclude", exclude.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        map.put("quantity", quantity);
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        exclude = ((Set<String>) map.getOrDefault("exclude", Set.of())).stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        quantity = (int) map.get("quantity");
    }
}
