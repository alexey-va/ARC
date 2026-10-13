package ru.arc.hooks.lootchest

import org.bukkit.Bukkit
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.arc.config.ConfigManager
import ru.arc.logging.ArcLogging.error

class LootChestHook {

    init {
        val config = ConfigManager.of(ArcDungeonsPlugin.instance.legacyDataPath.resolve("lootchests"), "bosses.yml")
        try {
            val listener = LootChestListener(config)
            Bukkit.getPluginManager().registerEvents(listener, ArcDungeonsPlugin.instance)
        } catch (e: Exception) {
            error("Error while initializing LootChestListener", e)
        }
    }
}
