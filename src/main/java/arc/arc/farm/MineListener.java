package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.hooks.ArcModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MineListener implements Listener, ArcModule {

    public List<Mine> mines = new ArrayList<>();

    public MineListener(){
        setupMines();
    }

    public void setupMines(){
        ConfigurationSection section = ARC.plugin.getConfig().getConfigurationSection("mine");
        if(section == null) return;
        for(String key : section.getKeys(false)){
            mines.add(new Mine(key));
        }
        mines.sort(Comparator.comparingInt(m -> -m.priority));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void farmBreakEvent(BlockBreakEvent event) {
        for(Mine mine : mines){
            if(mine.processBreakEvent(event)) break;
        }
    }

}
