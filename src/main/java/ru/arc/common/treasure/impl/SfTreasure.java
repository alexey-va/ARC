package ru.arc.common.treasure.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.hooks.HookRegistry;
import ru.arc.util.TextUtil;

import static ru.arc.util.Logging.error;

@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class SfTreasure extends Treasure {

    @Builder.Default
    int amountMin = 1, amountMax = 1;
    String id;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        ItemStack slimefunItemStack = HookRegistry.sfHook.getSlimefunItemStack(id);
        if(slimefunItemStack == null) {
            error("Slimefun item with id {} not found", id);
            return;
        }
        Set<Component> names = new HashSet<>();
        int maxStackSize = slimefunItemStack.getMaxStackSize();
        int left = amountMin == amountMax ? amountMin : ThreadLocalRandom.current().nextInt(amountMin, amountMax + 1);
        while (left > 0) {
            int toGive = Math.min(left, maxStackSize);
            ItemStack item = slimefunItemStack.clone();
            item.setAmount(toGive);
            player.getInventory().addItem(item).forEach((k, v) -> {
                Item item1 = player.getWorld().dropItem(player.getLocation(), v);
                item1.setOwner(player.getUniqueId());
            });
            left -= toGive;
            names.add(item.displayName());
        }
        if (flags.isSendMessage()) {
            Component message = config.componentDef("messages.treasure.slimefun.personal",
                    "<dark_green>Вы получили <yellow><amount><dark_green> предметов: <yellow><items>",
                    "<amount>", String.valueOf(amountMin == amountMax ? amountMin : amountMin + "-" + amountMax),
                    "<items>", TextUtil.toMM(TextUtil.join(names, "<gray>,</gray> ")));
            player.sendMessage(message);
        }
    }

    @Override
    protected Map<String, Object> serializeInternal() {
        return Map.of(
                "id", id,
                "amoumt", amountMin == amountMax ? amountMin : amountMin + "-" + amountMax,
                "type", "sf"
        );
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        id = (String) map.get("id");
        Object amount = map.getOrDefault("amoumt", 1);
        if (amount instanceof String) {
            String[] split = ((String) amount).split("-");
            amountMin = Integer.parseInt(split[0]);
            amountMax = Integer.parseInt(split[1]);
        } else if (amount instanceof Number) {
            amountMin = ((Number) amount).intValue();
            amountMax = amountMin;
        }
    }
}
