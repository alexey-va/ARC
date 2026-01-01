package ru.arc.hooks.citizens;

import net.citizensnpcs.api.event.NPCClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.arc.autobuild.BuildingManager;

public class CitizensListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcClick(NPCRightClickEvent event){
        processConstruction(event);
    }


    private void processConstruction(NPCClickEvent event){
        BuildingManager.INSTANCE.processNpcClick(event.getClicker(), event.getNPC().getId());
    }

}
