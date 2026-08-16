package ru.arc.onboarding

import com.Zrips.CMI.CMI
import com.Zrips.CMI.events.CMIUserHomeCreateEvent
import me.angeschossen.lands.api.events.ChunkPostClaimEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.Tasks
import ru.arc.core.delayed

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

internal class OnboardingCmiListener : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHomeCreated(event: CMIUserHomeCreateEvent) {
        val playerId = event.user.uniqueId
        val homeName = event.home.name
        delayed(1L) {
            val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return@delayed
            val user = CMI.getInstance().playerManager.getUser(playerId) ?: return@delayed
            if (user.getHome(homeName) == null) return@delayed
            OnboardingService.recordHomeCreated(player)
        }
    }
}

internal class OnboardingLandsListener : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkClaimed(event: ChunkPostClaimEvent) {
        val playerId = event.playerUID ?: return
        val action =
            Runnable {
                val player = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline } ?: return@Runnable
                OnboardingService.recordLandClaimed(player)
            }
        if (Bukkit.isPrimaryThread()) action.run() else Tasks.scheduler.runSync(action)
    }
}
