package arc.arc.bschests;

import arc.arc.configs.Config;
import arc.arc.generic.treasure.Treasure;
import arc.arc.generic.treasure.TreasureItem;
import arc.arc.generic.treasure.TreasurePool;
import arc.arc.network.repos.ItemList;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;

@Slf4j
public class ChestGenerator {

    Config config;

    public ChestGenerator(Config config) {
        this.config = config;
    }

    public ItemList generate(String poolName, Player player, int amount, int size) {
        TreasurePool pool = TreasurePool.getOrCreate(poolName);
        ItemList items = new ItemList();
        for (int i = 0; i < amount; i++) {
            Treasure treasure = pool.random();
            if (treasure instanceof TreasureItem treasureItem) {
                items.addAll(treasureItem.generateStacks());
            }
        }
        while (items.size() < size) {
            items.add(null);
        }
        spreadItems(items);
        log.info("Generated chest for {} with {} items {}", player.getName(), items.size(), items);
        return items;
    }

    private void spreadItems(ItemList list) {
        Collections.shuffle(list);
    }

}
