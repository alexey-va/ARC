package arc.arc.treasurechests;

import arc.arc.common.treasure.TreasurePool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class ChestType {
    Type type;
    String treasurePoolId;
    String particlePath;
    String namespaceId;
    int weight;

    public TreasurePool getTreasurePool() {
        return TreasurePool.getTreasurePool(treasurePoolId);
    }
}
