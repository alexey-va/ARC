package arc.arc.hooks.slimefun;

import arc.arc.configs.MainConfig;
import arc.arc.sync.SyncManager;
import arc.arc.util.TextUtil;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import lombok.extern.log4j.Log4j2;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class SFHook implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBackpack(PlayerRightClickEvent event) {
        SyncManager.processEvent(event);
    }

    public boolean isSlimefunBlock(Block block) {
        return Slimefun.getBlockDataService().getBlockData(block).isPresent();
    }

    public boolean isSlimefunItem(ItemStack stack) {
        return SlimefunItem.getByItem(stack) != null;
    }



}
