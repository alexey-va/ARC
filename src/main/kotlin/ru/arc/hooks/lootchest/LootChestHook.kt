package ru.arc.hooks.lootchest

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.util.Logging.error

class LootChestHook {

    init {
        val config = ConfigManager.of(ARC.instance.dataFolder.toPath().resolve("lootchests"), "bosses.yml")
        try {
            val listener = LootChestListener(config)
            Bukkit.getPluginManager().registerEvents(listener, ARC.instance)
        } catch (e: Exception) {
            error("Error while initializing LootChestListener", e)
        }
    }
}
