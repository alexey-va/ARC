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
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.error

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
            val home = user.getHome(homeName) ?: return@delayed
            val location = home.loc
            val verifiedFoothold =
                runCatching { HookRegistry.landsHook?.isProtectedFor(player, location) == true }
                    .onFailure { failure ->
                        error("Could not verify Lands protection for new CMI home of {}", player.name, failure)
                    }.getOrDefault(false)
            OnboardingService.recordHomeCreated(
                player,
                location.worldName,
                location.blockX,
                location.blockZ,
                verifiedFoothold,
            )
        }
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
