package arc.arc.common.chests;

import arc.arc.ARC;
import com.jeff_media.customblockdata.CustomBlockData;
import dev.lone.itemsadder.api.CustomFurniture;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

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
            log.error("Block at {} is not ItemsAdderCustomChest! Not removing!", block.getLocation());
            return;
        }

        data.remove(customChest);

        if (furniture == null) furniture = CustomFurniture.byAlreadySpawned(block);
        if (furniture == null) {
            System.out.println("No furniture at block " + block.getLocation());
            return;
        }
        furniture.remove(false);
    }
}
