package arc.arc.hooks.lootchest;

import arc.arc.configs.Config;
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity;
import fr.black_eyes.api.events.LootChestSpawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RequiredArgsConstructor
public class LootChestListener implements Listener {

    final Config lootChestsConfig;

    @EventHandler
    public void onLootChest(LootChestSpawnEvent event) {
        log.debug("Loot chest spawn event {}", event);
        Location location = event.getLc().getActualLocation().clone().add(0, 1, 0);
        List<String> bosses = lootChestsConfig.stringList("bosses");
        String bossFile = bosses.get(ThreadLocalRandom.current().nextInt(0, bosses.size()));
        if (bossFile == null) {
            log.error("No bosses found in config with name {}", "bosses");
            return;
        }
        RegionalBossEntity regionalBossEntity = RegionalBossEntity.SpawnRegionalBoss(bossFile, location);
        if (regionalBossEntity != null) regionalBossEntity.spawn(true);
        else log.error("Failed to spawn boss with file {}", bossFile);
    }

}
