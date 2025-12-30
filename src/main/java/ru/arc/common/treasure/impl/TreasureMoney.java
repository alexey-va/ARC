package ru.arc.common.treasure.impl;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;
import ru.arc.util.TextUtil;

@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class TreasureMoney extends Treasure {

    @Builder.Default
    double minAmount = 1, maxAmount = 1;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataPath(), "treasures.yml");

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        double amount = minAmount == maxAmount ? minAmount : ThreadLocalRandom.current().nextDouble(minAmount, maxAmount + 1);
        ARC.getEcon().depositPlayer(player, amount);

        if(flags.isSendMessage()) {
            sendPersonalMessage(player, amount);
        }
    }

    private void sendPersonalMessage(Player player, double amount) {
        String s = TextUtil.formatAmount(amount);
        var message = config.componentDef("messages.treasure.money.personal", "<dark_green>Вы получили <yellow><amount><dark_green> монет",
                "<amount>", s);
        player.sendMessage(message);
    }

    @Override
    protected Map<String, Object> serializeInternal() {
        return Map.of(
                "type", "money",
                "amount", minAmount == maxAmount ? minAmount : minAmount + "-" + maxAmount
        );
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        Object amount = map.getOrDefault("amount", 1.0);
        if (amount instanceof String) {
            String[] split = ((String) amount).split("-");
            if (split.length == 2) {
                minAmount = Double.parseDouble(split[0]);
                maxAmount = Double.parseDouble(split[1]);
            } else {
                minAmount = Double.parseDouble((String) amount);
                maxAmount = minAmount;
            }
        } else if (amount instanceof Number) {
            minAmount = ((Number) amount).doubleValue();
            maxAmount = minAmount;
        }
    }
}
