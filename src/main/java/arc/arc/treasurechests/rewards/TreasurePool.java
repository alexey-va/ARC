package arc.arc.treasurechests.rewards;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class TreasurePool {

    private TreeMap<Integer, Treasure> treasureMap = new TreeMap<>();
    private int totalWeight = 0;
    @Getter
    final String id;
    @Getter @Setter
    boolean dirty = false;

    public void add(Treasure treasure){
        totalWeight+=treasure.weight();
        treasureMap.put(totalWeight, treasure);
        dirty = true;
    }

    public Map<String, Object> serialize(){
        Map<String, Object> data = new HashMap<>();

        data.put("id", id);

        List<Map<String, Object>> treasures = new ArrayList<>();
        for(Treasure treasure : treasureMap.values()){
            Map<String, Object> map = treasure.serialize();
            treasures.add(map);
        }

        return data;
    }

    public Treasure random(){
        int rand = ThreadLocalRandom.current().nextInt(0, totalWeight+1);
        return treasureMap.ceilingEntry(rand).getValue();
    }

}
