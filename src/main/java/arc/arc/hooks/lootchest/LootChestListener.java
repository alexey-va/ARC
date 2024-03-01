package arc.arc.hooks.lootchest;

import arc.arc.configs.LootChestsConfig;
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity;
import fr.black_eyes.events.LootChestSpawnEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class LootChestListener implements Listener {

    public LootChestListener(){
        System.out.println("Creating listener for lootchest");
    }

    @EventHandler
    public void onLootChest(LootChestSpawnEvent event) {
        System.out.println("Spawned chest: "+event.getLc().getName());
        Location location = event.getLc().getActualLocation().clone().add(0, 1, 0);
        String bossFile = LootChestsConfig.randomBoss();
        if (bossFile == null) {
            System.out.println("Boss file is null!");
            return;
        }
        RegionalBossEntity regionalBossEntity = RegionalBossEntity.SpawnRegionalBoss(bossFile, location);
        if (regionalBossEntity != null) regionalBossEntity.spawn(true);
        else System.out.println("Could not spawn boss: "+bossFile);
    }

}
