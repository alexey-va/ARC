package arc.arc.eliteloot;

import arc.arc.ARC;
import lombok.Getter;
import lombok.ToString;

import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

@Getter
@ToString
public class DecorPool {

    TreeMap<Double, DecorItem> decors = new TreeMap<>();

    public void add(DecorItem decorItem, double weight){
        ARC.info("Adding decor item: " + decorItem + " with weight: " + weight);
        if(weight == 0) return;
        double lastKey = decors.isEmpty() ? 0 : decors.lastKey();
        decors.put(lastKey + weight, decorItem);
    }

    public DecorItem randomItem() {
        //ARC.info(decors.toString());
        if(decors.isEmpty()) return null;
        double random = ThreadLocalRandom.current().nextDouble(0, decors.lastKey());
        return decors.ceilingEntry(random).getValue();
    }

    public boolean contains(DecorItem decorItem) {
        return decors.containsValue(decorItem);
    }

    public void remove(DecorItem decorItem) {
        decors.values().removeIf(decorItem::equals);
    }
}
