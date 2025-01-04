package arc.arc.common.chests;

import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.block.Block;

@RequiredArgsConstructor
public abstract class CustomChest {

    final Block block;

    public abstract void create();
    public abstract void destroy();

    public Location getBlockLocation() {
        return block.getLocation();
    }

}
