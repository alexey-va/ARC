package ru.arc.common.treasure;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.arc.common.treasure.impl.*;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static ru.arc.util.Logging.error;

@Slf4j
@Data
public abstract class Treasure {

    private static final Map<String, Class<? extends Treasure>> types = Map.of(
            "item", TreasureItem.class,
            "sub-pool", SubPoolTreasure.class,
            "command", TreasureCommand.class,
            "enchant", TreasureEnchant.class,
            "ae-enchant", AEEnchant.class,
            "sf", SfTreasure.class,
            "potion", TreasurePotion.class,
            "money", TreasureMoney.class
    );

    public Map<String, Object> attributes = new HashMap<>();
    public int weight;
    public TreasurePool pool;

    public Optional<String> message() {
        String s = (String) attributes.get("message");
        if(s != null && s.isEmpty()){
            return Optional.empty();
        }
        return Optional.ofNullable((String) attributes.get("message"));
    }

    public void message(String message) {attributes.put("message", message);}

    public boolean announce() {return attributes.getOrDefault("announce", false).equals(Boolean.TRUE);}

    public void announce(boolean announce) {attributes.put("announce", announce);}

    public Optional<String> globalMessage() {
        String s = (String) attributes.get("globalMessage");
        if(s != null && s.isEmpty()){
            return Optional.empty();
        }
        return Optional.ofNullable((String) attributes.get("globalMessage"));
    }

    public double chance() {
        return getWeight() / (double) pool.getTotalWeight();
    }

    public void globalMessage(String globalMessage) {attributes.put("globalMessage", globalMessage);}

    public abstract void give(Player player, @NotNull GiveFlags flags);
    public void give(Player player) {give(player, GiveFlags.builder().build());}

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>(serializeInternal());
        map.put("weight", weight);
        map.put("attributes", attributes);
        return map;
    }


    protected abstract Map<String, Object> serializeInternal();

    protected abstract void setFields(Map<String, Object> map);

    public static Treasure from(Map<String, Object> map, TreasurePool treasurePool) {
        String type = (String) map.get("type");
        Class<? extends Treasure> clazz = types.get(type);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown treasure type: " + type);
        }
        try {
            Constructor<? extends Treasure> constructor = clazz.getConstructor();
            Treasure treasure = constructor.newInstance();
            int weight = (int) map.getOrDefault("weight", 1);
            Map<String, Object> attributes = (Map<String, Object>) map.getOrDefault("attributes", new HashMap<>());
            treasure.setWeight(weight);
            treasure.setAttributes(attributes);
            treasure.setFields(map);
            treasure.setPool(treasurePool);
            return treasure;
        } catch (Exception e) {
            error("Error while creating treasure", e);
        }
        return null;
    }
}
