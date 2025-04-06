package ru.arc.common.treasure.impl;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.text.StrBuilder;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.arc.ARC;
import ru.arc.common.WeightedRandom;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.configs.Config;
import ru.arc.configs.ConfigManager;

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
@EqualsAndHashCode(callSuper = true)
public class AEEnchant extends Treasure {

    Type type;
    String itemName;
    List<Object> args = new ArrayList<>();
    int amountMin = 1, amountMax = 1;

    private static final Config config = ConfigManager.of(ARC.plugin.getDataFolder().toPath(), "treasures.yml");

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        int randomAmount = amountMin == amountMax ? amountMin : ThreadLocalRandom.current().nextInt(amountMin, amountMax + 1);
        for(int i = 0; i < randomAmount; i++) {
            StrBuilder strBuilder = new StrBuilder();
            if (type == Type.ITEM) {
                strBuilder.append("ae giveitem ")
                        .append(player.getName()).append(" ")
                        .append(itemName).append(" ");
                for (Object arg : args) {
                    strBuilder.append(getArg(arg)).append(" ");
                }
            } else {
                strBuilder.append("ae giverandombook ")
                        .append(player.getName()).append(" ");
                for (Object arg : args) {
                    strBuilder.append(getArg(arg)).append(" ");
                }
            }
            String command = strBuilder.toString().trim();
            ARC.trySeverCommand(command);
        }
        if(flags.isSendMessage()) {
            var s = config.componentDef("messages.treasure.aeenchant.personal", "<dark_green>Вы получили <yellow><amount><dark_green> предметов",
                    "<amount>", String.valueOf(randomAmount));
            player.sendMessage(s);
        }
    }

    @Override
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "ae-enchant");
        map.put("item-name", itemName);
        map.put("args", args);
        map.put("enchant-type", type.name());
        map.put("amount", amountMax == amountMin ? amountMin : amountMin + "-" + amountMax);
        return map;
    }

    private String getArg(Object o) {
        if(o instanceof Integer) {
            return String.valueOf(o);
        } else if(o instanceof GaussData data) {
            return Math.round(data.random())+"";
        } else if(o instanceof RLevel) {
            Map<String, Integer> map = config.map("rlevels", Map.of(
                    "SIMPLE", 50,
                    "UNIQUE", 15,
                    "ELITE", 14,
                    "ULTIMATE", 9,
                    "LEGENDARY", 9,
                    "FABLED", 3
            ));
            WeightedRandom<String> weightedRandom = new WeightedRandom<>();
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                weightedRandom.add(entry.getKey(), entry.getValue());
            }
            return weightedRandom.random();
        } else if (o instanceof RSlot) {
            WeightedRandom<String> weightedRandom = new WeightedRandom<>();
            weightedRandom.add("WEAPON", 1);
            weightedRandom.add("TOOL", 1);
            weightedRandom.add("ARMOR", 1);
            return weightedRandom.random();
        }
        return "";
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        this.itemName = (String) map.get("item-name");
        this.type = Type.valueOf(((String) map.getOrDefault("enchant-type", "ITEM")).toUpperCase());
        Object amount = map.getOrDefault("amoumt", 1);
        if (amount instanceof String) {
            String[] split = ((String) amount).split("-");
            amountMin = Integer.parseInt(split[0]);
            amountMax = Integer.parseInt(split[1]);
        } else if (amount instanceof Number) {
            amountMin = ((Number) amount).intValue();
            amountMax = amountMin;
        }
        List<Object> unparsed = (List<Object>) map.getOrDefault("args", List.of());
        for(Object o : unparsed) {
            if(o instanceof Integer) {
                args.add(o);
            } else if(o instanceof String str) {
                String[] split = str.split(";");
                String type = split[0];
                if(type.equalsIgnoreCase("gauss")) {
                    int min = Integer.parseInt(split[1]);
                    int max = Integer.parseInt(split[2]);
                    int mean = Integer.parseInt(split[3]);
                    int std = Integer.parseInt(split[4]);
                    GaussData data = new GaussData((double) min, (double) max, (double) mean, (double) std);
                    args.add(data);
                } else if(type.equalsIgnoreCase("rlevel")) {
                    args.add(new RLevel());
                } else if (type.equalsIgnoreCase("rslot")) {
                    args.add(new RSlot());
                }
            }

        }
    }

    enum Type {
        ITEM, RANDOM_BOOK
    }

    static class RLevel {}

    static class RSlot {}

}
