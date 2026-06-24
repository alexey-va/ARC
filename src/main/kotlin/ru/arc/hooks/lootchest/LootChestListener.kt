package ru.arc.hooks.lootchest

import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity
import fr.black_eyes.api.events.LootChestSpawnEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.config.Config
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import java.util.concurrent.ThreadLocalRandom

class LootChestListener(private val lootChestsConfig: Config) : Listener {

    @EventHandler
    fun onLootChest(event: LootChestSpawnEvent) {
        debug("Loot chest spawn event {}", event)
        val location = event.lc.actualLocation.clone().add(0.0, 1.0, 0.0)
        val bosses = lootChestsConfig.stringList("bosses")
        if (bosses.isEmpty()) {
            error("No bosses found in config with name {}", "bosses")
            return
        }
        val bossFile = bosses[ThreadLocalRandom.current().nextInt(0, bosses.size)]
        val boss = RegionalBossEntity.SpawnRegionalBoss(bossFile, location)
        if (boss != null) boss.spawn(true)
        else error("Failed to spawn boss with file {}", bossFile)
    }
}
