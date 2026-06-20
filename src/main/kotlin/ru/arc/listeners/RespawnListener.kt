package ru.arc.listeners

import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerRespawnEvent
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

class RespawnListener : Listener {

    private val config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        info("Respawning player {} reason {} location {}", event.player.name, event.respawnReason, event.respawnLocation)
        val world = event.player.location.world
        info("World {}", world.name)
        val interceptWorlds = config.stringSet("respawn-intercept-worlds")
        if (interceptWorlds.contains(world.name)) {
            info("Intercepting respawn for player {}", event.player.name)
            val respawnLocation = event.player.getRespawnLocation()
            info("Respawn location {}", respawnLocation)
            if (respawnLocation == null) return
            event.respawnLocation = respawnLocation
        }
    }

    @EventHandler
    fun onBedUse(e: PlayerInteractEvent) {
        val player = e.player
        if (e.action == Action.RIGHT_CLICK_BLOCK) {
            val clickedBlock = e.clickedBlock ?: return
            if (!Tag.BEDS.isTagged(clickedBlock.type)) return
            if ((player.world.time < 12541 || player.world.time > 23458) && !player.world.hasStorm()) {
                e.isCancelled = true
                info("Player {} tried to sleep during the day", player.name)
            }
            val oldRespawn = player.getRespawnLocation()
            HookRegistry.huskHomesHook!!.hasHome(player).thenAccept { hasHome ->
                info("Player {} has home {}", player.name, hasHome)
                try {
                    if (!hasHome) {
                        HookRegistry.huskHomesHook!!.createDefaultHome(player, player.location)
                        player.sendMessage(config.component("rtp-respawn.bed-create-home", "<green>Ваш <gold>/home<green> установлен здесь! <gray>Чтобы изменить его, используйте команду /sethome"))
                    } else {
                        ARC.instance.server.scheduler.runTaskLater(ARC.instance, Runnable {
                            info("Setting respawn location for player {} to {}", player.name, oldRespawn)
                            player.setRespawnLocation(oldRespawn, true)
                        }, 3L)
                    }
                } catch (ex: Exception) {
                    error("Error setting respawn location for player {}", player.name, ex)
                }
            }
        }
    }
}
