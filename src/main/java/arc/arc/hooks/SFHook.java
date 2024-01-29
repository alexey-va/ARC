package arc.arc.hooks;

import arc.arc.ARC;
import arc.arc.Config;
import arc.arc.util.TextUtil;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public class SFHook implements Listener, ArcModule {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseBackpack(PlayerRightClickEvent event) {
        if (!Config.blockBackpacks) return;
        Optional<SlimefunItem> optional = event.getSlimefunItem();
        if (optional.isPresent()) {
            SlimefunItem item = optional.get();
            if (item.getId().contains("BACKPACK") && !item.getId().equalsIgnoreCase("ENDER_BACKPACK")) {
                event.cancel();
                event.getPlayer().sendMessage(TextUtil.noWGPermission());
            }
        }
    }

    public boolean isSlimefunItem(ItemStack stack) {
        return SlimefunItem.getByItem(stack) != null;
    }

}
