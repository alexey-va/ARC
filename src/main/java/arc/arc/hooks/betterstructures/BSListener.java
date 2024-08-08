package arc.arc.hooks.betterstructures;

import arc.arc.bschests.PersonalLootManager;
import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@Slf4j
public class BSListener implements Listener {

    @EventHandler
    public void onChestFill(ChestFillEvent event) {
        Block block = event.getContainer().getBlock();
        System.out.println("Chest fill event at " + block.getLocation());
        log.info("Chest fill event at {}", block.getLocation());
        PersonalLootManager.processChestGen(block);
    }

    @EventHandler
    public void onStructureGen(BuildPlaceEvent event) {
        log.info("Structure gen event at {}", event.getFitAnything().getLocation());
        log.info("Clipboard: {}", event.getFitAnything().getSchematicClipboard());
        log.info("Vector: {}", event.getFitAnything().getSchematicOffset());
        log.info("", event.getFitAnything().getSchematicContainer());
    }

}
