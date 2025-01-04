package arc.arc.common.treasure.impl;

import arc.arc.ARC;
import arc.arc.configs.Config;
import arc.arc.configs.ConfigManager;
import arc.arc.common.treasure.GiveFlags;
import arc.arc.common.treasure.Treasure;
import arc.arc.hooks.HookRegistry;
import arc.arc.util.TextUtil;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.playerlist.PlayerManager;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static arc.arc.util.TextUtil.mm;

@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class TreasureItem extends Treasure {

    ItemStack stack;
    int quantity;
    GaussData gaussData;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasures.yml");

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
            //log.info("Sending global message to {} players", uuids.size());
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
        log.info("Sending global message {}", finalGlobalMessage);
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
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "item");
        map.put("quantity", quantity);
        if (gaussData != null) map.put("gaussData", gaussData.serialize());
        else map.put("gaussData", null);
        map.put("stack", stack.serialize());
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        quantity = (int) map.getOrDefault("quantity", 1);
        stack = ItemStack.deserialize((Map<String, Object>) map.get("stack"));
        Map<String, Double> gaussDataMap = (Map<String, Double>) map.get("gaussData");
        if (gaussDataMap != null) {
            gaussData = GaussData.deserialize(gaussDataMap);
        }
    }
}
