package ru.arc.common.treasure.impl;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
@Slf4j
public class TreasureEnchant extends Treasure {

    int minAmount = 1, maxAmount = 1;
    Set<String> exclude = new HashSet<>();

    private static final Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        List<Enchantment> list = Registry.ENCHANTMENT.stream()
                .filter(e -> !exclude.contains(e.key().asString().toLowerCase()))
                .toList();
        int randomAmount = minAmount == maxAmount ? minAmount : ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
        List<String> enchantNames = new ArrayList<>();
        for (int i = 0; i < randomAmount; i++) {
            Enchantment e = list.get(ThreadLocalRandom.current().nextInt(list.size()));
            ItemStack item = ItemStack.of(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
            meta.addStoredEnchant(e, ThreadLocalRandom.current().nextInt(e.getMaxLevel()) + 1, true);
            item.setItemMeta(meta);
            player.getInventory().addItem(item).forEach((k, v) -> {
                Item item1 = player.getWorld().dropItem(player.getLocation(), v);
                item1.setOwner(player.getUniqueId());
            });
            enchantNames.add(e.key().toString());
        }
        log.info("Player {} got {} enchantments: {}", player.getName(), randomAmount, enchantNames);

        if (flags.isSendMessage()) {
            Component s = config.componentDef("messages.treasure.enchant.personal", "<dark_green>Вы получили <yellow><amount><dark_green> предметов",
                    "<amount>", String.valueOf(randomAmount));
            player.sendMessage(s);
        }

    }


    @Override
    public Map<String, Object> serializeInternal() {
        HashMap<String, Object> map = new HashMap<>();
        map.put("exclude", exclude.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        map.put("type", "enchant");
        map.put("amount", minAmount == maxAmount ? minAmount : minAmount + "-" + maxAmount);
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        exclude = ((Set<String>) map.getOrDefault("exclude", Set.of())).stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        Object amount = map.getOrDefault("amount", 1);
        if (amount instanceof String) {
            String[] split = ((String) amount).split("-");
            if (split.length == 2) {
                minAmount = Integer.parseInt(split[0]);
                maxAmount = Integer.parseInt(split[1]);
            } else {
                minAmount = Integer.parseInt((String) amount);
                maxAmount = minAmount;
            }
        } else if (amount instanceof Integer) {
            minAmount = (int) amount;
            maxAmount = minAmount;
        }
    }
}
