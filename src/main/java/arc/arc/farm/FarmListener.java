package arc.arc.farm;

import arc.arc.hooks.ArcModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class FarmListener implements Listener, ArcModule {
    public Farm farm;

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event){
        farm.processBreakEvent(event);
    }

}
