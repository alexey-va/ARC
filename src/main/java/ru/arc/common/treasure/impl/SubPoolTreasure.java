package ru.arc.common.treasure.impl;

import java.util.HashMap;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.common.treasure.TreasurePool;

import static ru.arc.util.Logging.error;

@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubPoolTreasure extends Treasure {

    String subPoolId;

    @Override
    public void give(Player player, @NotNull GiveFlags flags) {
        TreasurePool treasurePool = TreasurePool.getTreasurePool(subPoolId);
        if (treasurePool != null) {
            treasurePool.random().give(player, flags);
        } else {
            error("Sub pool {} not found", subPoolId);
        }
    }

    @Override
    public Map<String, Object> serializeInternal() {
        Map<String, Object> map = new HashMap<>();
        map.put("sub-pool-id", subPoolId);
        map.put("type", "sub-pool");
        return map;
    }

    @Override
    protected void setFields(Map<String, Object> map) {
        this.subPoolId = (String) map.get("sub-pool-id");
    }
}
