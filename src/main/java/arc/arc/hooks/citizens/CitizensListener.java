package arc.arc.hooks.citizens;

import arc.arc.autobuild.BuildingManager;
import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class CitizensListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcClick(NPCRightClickEvent event){
        processConstruction(event);
    }


    private void processConstruction(NPCClickEvent event){
        BuildingManager.processNpcClick(event.getClicker(), event.getNPC().getId());
    }

}
