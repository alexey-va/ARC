package ru.arc.common.chests;

import com.jeff_media.customblockdata.CustomBlockData;
import dev.lone.itemsadder.api.CustomFurniture;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.arc.ARC;

import java.util.ArrayList;
import java.util.List;

import static ru.arc.util.Logging.error;

@Slf4j
public class ItemsAdderCustomChest extends CustomChest {

    CustomFurniture furniture;
    final String namespaceId;

    public ItemsAdderCustomChest(Block block, String namespaceId) {
        super(block);
        this.namespaceId = namespaceId;
    }

    @Override
    public void create() {
        if (block.getType() != Material.AIR) {
            System.out.println("Block at " + block.getLocation() + " is not air! Not placing!");
            return;
        }

        furniture = CustomFurniture.spawn(namespaceId, block);
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        data.set(new NamespacedKey(ARC.plugin, "custom_chest"), PersistentDataType.STRING, "ia");
    }

    @Override
    public void destroy() {
        NamespacedKey customChest = new NamespacedKey(ARC.plugin, "custom_chest");
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);

        String s = data.get(customChest, PersistentDataType.STRING);
        if (s == null || !s.equals("ia")) {
            error("Block at {} is not ItemsAdderCustomChest! Not removing!", block.getLocation());
            return;
        }

        data.remove(customChest);

        if (furniture == null) furniture = CustomFurniture.byAlreadySpawned(block);
        if (furniture == null) {
            System.out.println("No furniture at block " + block.getLocation());
            tryCleanup();
            return;
        }
        furniture.remove(false);
        tryCleanup();
    }

    private void tryCleanup() {
        try {
            var frames = block.getLocation().getNearbyEntitiesByType(ItemFrame.class, 1.5);
            for (var frame : frames) {
                if (frame.isVisible()) continue;
                ItemStack item = frame.getItem();
                if (item.getType().isAir()) continue;
                if (!item.getItemMeta().hasCustomModelDataComponent()) continue;
                frame.remove();
            }
            List<Block> blocks = new ArrayList<>();
            blocks.add(block);
            blocks.add(block.getRelative(0, 1, 0));
            blocks.add(block.getRelative(0, -1, 0));
            blocks.add(block.getRelative(1, 0, 0));
            blocks.add(block.getRelative(-1, 0, 0));
            blocks.add(block.getRelative(0, 0, 1));
            blocks.add(block.getRelative(0, 0, -1));
            for (var b : blocks) {
                if(b.getType() != Material.BARRIER) continue;
                b.setType(Material.AIR);
            }
        } catch (Exception e) {
            error("Error cleaning up item frames", e);
        }
    }
}
