package ru.arc.common.treasure.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
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
import ru.arc.xserver.announcements.AnnounceManager;
import ru.arc.xserver.playerlist.PlayerManager;

import static ru.arc.util.Logging.error;
import static ru.arc.util.Logging.info;
import static ru.arc.util.TextUtil.mm;

@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class TreasureItem extends Treasure {

    ItemStack stack;
    @Builder.Default
    int minAmount = 1, maxAmount = 1;
    GaussData gaussData;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasures.yml");

    /**
     * Creates a new TreasureItem with the given parameters.
     * Use this instead of the Lombok builder for Kotlin compatibility.
     */
    public static TreasureItem create(ItemStack stack, int minAmount, int maxAmount, GaussData gaussData) {
        TreasureItem item = new TreasureItem();
        item.stack = stack;
        item.minAmount = minAmount;
        item.maxAmount = maxAmount;
        item.gaussData = gaussData;
        return item;
    }

    /**
     * Creates a new TreasureItem with a single amount.
     */
    public static TreasureItem create(ItemStack stack, int amount) {
        return create(stack, amount, amount, null);
    }

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        List<ItemStack> stacks = generateStacks();

        int amount = stacks.stream().mapToInt(ItemStack::getAmount).sum();

        if (flags.isSendMessage()) sendPersonalMessages(player, amount);
        if (flags.isSendGlobalMessage()) {
            Set<UUID> uuids;
            if (flags.isEntireServerAnnounce()) uuids = PlayerManager.getPlayerUuids();
            else if (flags.isWorldAnnounce())
                uuids = player.getWorld().getPlayers().stream()
                        .map(Player::getUniqueId)
                        .filter(uuid -> !uuid.equals(player.getUniqueId()))
                        .collect(Collectors.toSet());
            else {
                double radius = flags.getRadiusAnnounce();
                uuids = player.getLocation().getNearbyPlayers(radius).stream()
                        .map(Player::getUniqueId)
                        .collect(Collectors.toSet());
            }
            //info("Sending global message to {} players", uuids.size());
            sendGlobalMessage(player, amount, uuids);
        }

        player.getInventory()
                .addItem(stacks.toArray(ItemStack[]::new))
                .values()
                .forEach(st -> player.getWorld().dropItem(player.getLocation(), st));
    }

    private void sendPersonalMessages(Player player, int amount) {
        String message = this.message().orElse(null);
        if (message == null) message = pool.getCommonMessage();
        message = setPlaceholders(message, player, amount);

        if (message == null) return;
        if (message.isBlank()) return;
        player.sendMessage(mm(message));
    }

    private void sendGlobalMessage(Player player, int amount, Collection<UUID> players) {
        boolean announce = this.announce() || pool.isCommonAnnounce();
        String globalMessage = this.globalMessage().orElse(null);
        if (globalMessage == null) globalMessage = pool.getCommonAnnounceMessage();
        globalMessage = setPlaceholders(globalMessage, player, amount);

        if (globalMessage == null) return;
        if (!announce) return;
        if (globalMessage.isBlank()) return;

        String finalGlobalMessage = globalMessage;
        info("Sending global message {}", finalGlobalMessage);
        players.forEach(uuid -> AnnounceManager.sendMessageGlobally(uuid, finalGlobalMessage));
    }

    private String setPlaceholders(String message, Player player, int amount) {
        if (message == null) return null;
        String itemCount = "";
        if (amount > 1) {
            itemCount += config.string("messages.item-amount", "%amount% x ")
                    .replace("%amount%", String.valueOf(amount));
        }

        String itemName = stack.getItemMeta().hasDisplayName() ? TextUtil.toMM(stack.displayName()) :
                (HookRegistry.translatorHook != null ? HookRegistry.translatorHook.translate(stack.getType()) : stack.getType().name());

        if (HookRegistry.papiHook != null) message = HookRegistry.papiHook.parse(message, player);
        return message
                .replace("%item%", itemName)
                .replace("%amount%", itemCount);
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


    public double generateAmount() {
        if (gaussData == null) {
            return minAmount == maxAmount ? minAmount : ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
        } else {
            if (gaussData.mean == null || gaussData.stdDev == null) {
                error("Gauss data is missing mean or stdDev");
                return minAmount == maxAmount ? minAmount : ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
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
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "item");
        map.put("amount", minAmount == maxAmount ? minAmount : minAmount + "-" + maxAmount);
        if (gaussData != null) map.put("gaussData", gaussData.serialize());
        else map.put("gaussData", null);
        map.put("stack", stack.serialize());
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        stack = ItemStack.deserialize((Map<String, Object>) map.get("stack"));
        Map<String, Double> gaussDataMap = (Map<String, Double>) map.get("gaussData");
        if (gaussDataMap != null) {
            gaussData = GaussData.deserialize(gaussDataMap);
        }
        Object amount = map.get("amount");
        Object quantityOld = map.get("quantity");
        if(amount == null && quantityOld != null) {
            amount = quantityOld;
        }
        if(amount == null) amount = 1;
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
