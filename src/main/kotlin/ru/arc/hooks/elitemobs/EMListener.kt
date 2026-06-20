package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteExplosionEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.configs.ConfigManager

class EMListener : Listener {

    private val config = ConfigManager.of(ARC.instance.dataPath, "elitemobs.yml")

    @EventHandler
    fun emExplosion(event: EliteExplosionEvent) {
        val noExpWorlds = config.stringList("no-explosion-worlds")
        val name = event.explosionSourceLocation.world.name
        if (noExpWorlds.contains(name)) event.isCancelled = true
    }
}
