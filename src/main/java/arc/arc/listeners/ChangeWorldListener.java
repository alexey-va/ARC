package arc.arc.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerChangedWorldEvent;

import java.util.EventListener;

public class ChangeWorldListener implements EventListener {

    @EventHandler
    public void changeWorldEvent(PlayerChangedWorldEvent event){
        processTreasureHuntBossbar(event);
    }

    private void processTreasureHuntBossbar(PlayerChangedWorldEvent event){

    }

}
