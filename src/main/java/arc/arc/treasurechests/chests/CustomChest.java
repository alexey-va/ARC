package arc.arc.treasurechests.chests;

import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;

@RequiredArgsConstructor
public abstract class CustomChest {

    final Block block;


    public abstract void create();
    public abstract void destroy();


}
