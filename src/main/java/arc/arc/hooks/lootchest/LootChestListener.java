package arc.arc.hooks.lootchest;

import arc.arc.configs.Config;
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity;
import fr.black_eyes.events.LootChestSpawnEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@RequiredArgsConstructor
public class LootChestListener implements Listener {

    final Config lootChestsConfig;

    @EventHandler
    public void onLootChest(LootChestSpawnEvent event) {
        System.out.println("Spawned chest: "+event.getLc().getName());
        Location location = event.getLc().getActualLocation().clone().add(0, 1, 0);
        List<String> bosses = lootChestsConfig.stringList("bosses");
        String bossFile = bosses.get(ThreadLocalRandom.current().nextInt(0, bosses.size()));
        if (bossFile == null) {
            System.out.println("Boss file is null!");
            return;
        }
        RegionalBossEntity regionalBossEntity = RegionalBossEntity.SpawnRegionalBoss(bossFile, location);
        if (regionalBossEntity != null) regionalBossEntity.spawn(true);
        else System.out.println("Could not spawn boss: "+bossFile);
    }

}
