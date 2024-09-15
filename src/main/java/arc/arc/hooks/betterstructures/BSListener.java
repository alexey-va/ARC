package arc.arc.hooks.betterstructures;

import arc.arc.bschests.PersonalLootManager;
import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import static com.magmaguy.betterstructures.config.generators.GeneratorConfigFields.StructureType.SURFACE;

@Slf4j
public class BSListener implements Listener {

    Set<ChunkCoords> genCoords = new ConcurrentSkipListSet<>();
    int r = 7;

    @EventHandler
    public void onChestFill(ChestFillEvent event) {
        //System.out.println(event);
        Block block = event.getContainer().getBlock();
        //log.info("Chest fill event at {}", block.getLocation());
        //log.info("Chest fill event at {}", block.getLocation());
        PersonalLootManager.processChestGen(block);
    }


    record ChunkCoords(int x, int z) implements Comparable<ChunkCoords> {
        @Override
        public int compareTo(ChunkCoords o) {
            return x == o.x ? Integer.compare(z, o.z) : Integer.compare(x, o.x);
        }
    }

    @EventHandler
    public void onStructureGen(BuildPlaceEvent event) {
        List<GeneratorConfigFields.StructureType> structureTypes = event.getFitAnything().getSchematicContainer().getGeneratorConfigFields().getStructureTypes();
        if (!structureTypes.contains(SURFACE)) {
            return;
        }
        if (event.getFitAnything().getLocation().y() < 64) {
            return;
        }

        Chunk chunk = event.getFitAnything().getLocation().clone().getChunk();
        ChunkCoords coords = new ChunkCoords(chunk.getX(), chunk.getZ());
        // check if chunk in radius r has already been generated
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (genCoords.contains(new ChunkCoords(coords.x + x, coords.z + z))) {
                    event.setCancelled(true);
                }
            }
        }
        genCoords.add(coords);
    }

}
