package ru.arc.common.treasure.impl;

import ru.arc.common.treasure.GiveFlags;
import ru.arc.common.treasure.Treasure;
import ru.arc.common.treasure.TreasurePool;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Slf4j
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
            log.error("Sub pool {} not found", subPoolId);
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
