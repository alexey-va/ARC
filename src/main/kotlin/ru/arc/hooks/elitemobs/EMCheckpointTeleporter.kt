package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.instanced.MatchInstance
import com.magmaguy.elitemobs.instanced.InstancePlayerMovement
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.arc.paper.teleport.TeleportMatchTolerance

/**
 * Use EliteMobs' player/destination authorization and reject nested or redirected events.
 * Other plugins' cancellations remain authoritative; instance membership is never changed.
 */
internal class EMCheckpointTeleporter : Listener {
    private val allowed = ScopedTeleportAuthorizer(TeleportMatchTolerance(0.0, 0f))
    private var active = false
    private var owned: PlayerTeleportEvent? = null

    fun teleport(player: Player, destination: Location, instance: Boolean): Boolean {
        check(Bukkit.isPrimaryThread())
        if (active || player.world.uid != destination.world.uid) return false
        if (instance) {
            val match = MatchInstance.getPlayerInstance(player) ?: return false
            if (match.isCancelled || match.state != MatchInstance.InstancedRegionState.ONGOING ||
                (match as? DungeonInstance)?.world != player.world || player !in match.players) return false
        }
        active = true
        return try {
            allowed.authorize(player.uniqueId, destination) {
                InstancePlayerMovement.teleportWithinWorld(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
            }
        } finally {
            owned = null
            active = false
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun admit(event: PlayerTeleportEvent) {
        if (!active) return
        if (owned != null || !matches(event)) { event.isCancelled = true; return }
        owned = event
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun rejectRedirect(event: PlayerTeleportEvent) {
        if (active && (event !== owned || !matches(event))) event.isCancelled = true
    }

    internal fun owns(event: PlayerTeleportEvent): Boolean = active && owned === event

    private fun matches(event: PlayerTeleportEvent): Boolean =
        event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN && event.from.world.uid == event.to.world.uid &&
            allowed.isAuthorized(event.player.uniqueId, event.to)

}
