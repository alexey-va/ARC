package arc.arc.listeners;

import arc.arc.ARC;
import arc.arc.eliteloot.EliteLootManager;
import arc.arc.hooks.HookRegistry;
import com.magmaguy.elitemobs.api.utils.EliteItemManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;

public class PickupListener implements Listener {


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemPickup(PlayerAttemptPickupItemEvent event) {
        if(HookRegistry.emHook == null) return;
        ItemStack stack = event.getItem().getItemStack();
        ItemStack stack1 = EliteLootManager.getEliteLootProcessor().processEliteLoot(stack);
        //ARC.info("Item: " + stack + " -> " + stack1);
        if (stack1 != null && stack1 != stack) {
            event.getItem().setItemStack(stack1);
        }
    }

}
