package arc.arc.treasurechests.chests;

import arc.arc.ARC;
import com.jeff_media.customblockdata.CustomBlockData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

public class VanillaChest extends CustomChest{


    public VanillaChest(Block block) {
        super(block);
    }

    @Override
    public void create() {
        if(block.getType() != Material.AIR){
            System.out.println("Block at " +block.getLocation() +" is not air! Not placing!");
            return;
        }

        block.setType(Material.CHEST);
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        data.set(new NamespacedKey(ARC.plugin,"custom_chest"), PersistentDataType.STRING, "vanilla");
    }

    @Override
    public void destroy() {
        CustomBlockData data = new CustomBlockData(block, ARC.plugin);
        data.remove(new NamespacedKey(ARC.plugin,"custom_chest"));
        if(block.getType() != Material.CHEST){
            System.out.println("Block at " +block.getLocation() +" is not chest! Not removing!");
            return;
        }

        block.setType(Material.AIR);
    }
}
