package arc.arc.farm;

import arc.arc.ARC;
import arc.arc.configs.FarmConfig;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FarmManager {

    private List<Mine> mines = new ArrayList<>();
    private Farm farm;
    private Lumbermill lumbermill;
    private BukkitTask clearLimitTask;
    private int lastResetHour = 0;
    private FarmConfig farmConfig;

    public FarmManager() {
    }

    public void init() {
        setupTasks();
        farmConfig = new FarmConfig(this);
    }

    public void addMine(Mine mine) {
        mines.stream()
                .filter(m -> Objects.equals(m.getMineId(), mine.getMineId()))
                .findAny()
                .ifPresentOrElse(m -> System.out.println("Mine with id " + mine.getMineId() + " is already defined!"),
                        () -> {
                            System.out.println("Loaded mine " + mine.getMineId() + " from config!");
                            mines.add(mine);
                        });
    }

    public void addFarm(Farm farm) {
        if (this.farm != null) {
            System.out.println("One farm is already defined!");
        } else {
            System.out.println("Loaded farm from config!");
            this.farm = farm;
        }
    }

    public void addLumber(Lumbermill lumbermill) {
        if (this.lumbermill != null) {
            System.out.println("One lumbermill is already defined!");
        } else {
            System.out.println("Loaded lumbermill from config!");
            this.lumbermill = lumbermill;
        }
    }

    private void setupTasks() {
        cancelTasks();

        clearLimitTask = new BukkitRunnable() {
            @Override
            public void run() {
                int currentHour = LocalTime.now().getHour();
                if (currentHour != lastResetHour) {
                    lastResetHour = currentHour;
                    if (farm != null) farm.resetLimit();
                    mines.forEach(Mine::resetLimit);
                }
            }
        }.runTaskTimer(ARC.plugin, 0L, 60L * 20L);
    }

    public void cancelTasks() {
        if (clearLimitTask != null && !clearLimitTask.isCancelled()) clearLimitTask.cancel();
    }

    public void clear() {
        mines.forEach(Mine::cancelTasks);
    }

    public void processEvent(BlockBreakEvent event) {
        if(farm != null && farm.processBreakEvent(event)) return;
        if(lumbermill != null && lumbermill.processBreakEvent(event)) return;
        for(Mine mine : mines){
            if(mine.processBreakEvent(event)) return;
        }
    }

}
