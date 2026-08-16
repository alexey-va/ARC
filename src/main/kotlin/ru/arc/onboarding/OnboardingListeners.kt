package ru.arc.onboarding

import me.angeschossen.lands.api.events.ChunkPostClaimEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.Tasks

internal class OnboardingPlayerListener : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        OnboardingService.resume(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChanged(event: PlayerChangedWorldEvent) {
        OnboardingService.resume(event.player)
    }
}

internal class OnboardingLandsListener : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkClaimed(event: ChunkPostClaimEvent) {
        val playerId = event.playerUID ?: return
        val worldName = event.world.name
        val chunkX = event.x
        val chunkZ = event.z
        val action =
            Runnable {
                val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return@Runnable
                OnboardingService.recordLandClaimed(player, worldName, chunkX, chunkZ)
            }
        if (Bukkit.isPrimaryThread()) action.run() else Tasks.scheduler.runSync(action)
    }
}
