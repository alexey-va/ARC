package ru.arc.common.treasure.impl;

import lombok.*;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class TreasurePotion extends Treasure {

    int minAmount = 1, maxAmount = 1;

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        int amount = minAmount == maxAmount ? minAmount : ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
        for (int i = 0; i < amount; i++) {
            ItemStack potion = getRandomPotion();
            player.getInventory().addItem(potion).forEach((k, v) -> {
                Item item1 = player.getWorld().dropItem(player.getLocation(), v);
                item1.setOwner(player.getUniqueId());
            });
        }
    }

    @Override
    protected Map<String, Object> serializeInternal() {
        return Map.of(
                "type", "potion",
                "amount", minAmount == maxAmount ? minAmount : minAmount + "-" + maxAmount
        );
    }

    @Override
    protected void setFields(Map<String, Object> map) {
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

    public static ItemStack getRandomPotion() {
        List<PotionType> validTypes = Registry.POTION.stream()
                .filter(type ->
                        type != PotionType.WATER
                                && type != PotionType.AWKWARD
                                && type != PotionType.MUNDANE
                                && type != PotionType.THICK)
                .toList();

        PotionType potionType = validTypes.get(ThreadLocalRandom.current().nextInt(validTypes.size()));

        // Random splash/lingering/regular
        Material[] materials = {
                Material.POTION,
                Material.SPLASH_POTION,
                Material.LINGERING_POTION
        };
        Material bottleType = materials[ThreadLocalRandom.current().nextInt(materials.length)];
        ItemStack potion = ItemStack.of(bottleType);

        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        meta.setBasePotionType(potionType);
        potion.setItemMeta(meta);

        return potion;
    }
}
