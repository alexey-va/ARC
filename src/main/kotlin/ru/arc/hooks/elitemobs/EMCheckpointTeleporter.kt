package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.instanced.MatchInstance
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
 * EliteMobs 10.1.1 / 10.7.3 / 10.8.1 only exposes a global, one-event teleportBypass, consumed at
 * LOW. Arm it at LOWEST for one exact synchronous event, reject nested events,
 * and always clear it in finally. Other plugins' cancellations are never undone.
 * Cleanup runs at NORMAL, after native LOW regardless of deferred native registration.
 * Used only on the primary thread.
 */
internal class EMCheckpointTeleporter : Listener {
    private val allowed = ScopedTeleportAuthorizer(TeleportMatchTolerance(0.0, 0f))
    private var active = false
    private var instanced = false
    private var owned: PlayerTeleportEvent? = null
    private var adminOwned: PlayerTeleportEvent? = null
    private var adminMatch: MatchInstance? = null

    fun teleport(player: Player, destination: Location, instance: Boolean): Boolean {
        check(Bukkit.isPrimaryThread())
        if (active || MatchInstance.MatchInstanceEvents.teleportBypass || player.world.uid != destination.world.uid) return false
        if (instance) {
            val match = MatchInstance.getPlayerInstance(player) ?: return false
            if (match.isCancelled || match.state != MatchInstance.InstancedRegionState.ONGOING ||
                (match as? DungeonInstance)?.world != player.world || player !in match.players) return false
        }
        active = true
        instanced = instance
        return try {
            allowed.authorize(player.uniqueId, destination) {
                player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
            }
        } finally {
            MatchInstance.MatchInstanceEvents.teleportBypass = false
            owned = null
            instanced = false
            active = false
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun admit(event: PlayerTeleportEvent) {
        if (!active) {
            if (adminOwned != null) {
                event.isCancelled = true
                return
            }
            val match = adminMatch(event) ?: return
            adminOwned = event
            adminMatch = match
            MatchInstance.MatchInstanceEvents.teleportBypass = true
            return
        }
        if (owned != null || !matches(event)) { event.isCancelled = true; return }
        owned = event
        if (instanced && !event.isCancelled) MatchInstance.MatchInstanceEvents.teleportBypass = true
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun afterNativeGuard(event: PlayerTeleportEvent) {
        if (active && instanced && event === owned) MatchInstance.MatchInstanceEvents.teleportBypass = false
        if (event === adminOwned) MatchInstance.MatchInstanceEvents.teleportBypass = false
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun rejectRedirect(event: PlayerTeleportEvent) {
        if (active && (event !== owned || !matches(event))) event.isCancelled = true
        val expectedMatch = adminMatch
        if (event === adminOwned && (expectedMatch == null || !matchesAdmin(event, expectedMatch))) event.isCancelled = true
        else if (adminOwned != null && event !== adminOwned) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun finishAdminTeleport(event: PlayerTeleportEvent) {
        if (event !== adminOwned) return
        MatchInstance.MatchInstanceEvents.teleportBypass = false
        adminOwned = null
        adminMatch = null
    }

    internal fun owns(event: PlayerTeleportEvent): Boolean = active && owned === event

    private fun matches(event: PlayerTeleportEvent): Boolean =
        event.cause == PlayerTeleportEvent.TeleportCause.PLUGIN && event.from.world.uid == event.to.world.uid &&
            allowed.isAuthorized(event.player.uniqueId, event.to)

    private fun adminMatch(event: PlayerTeleportEvent): MatchInstance? {
        if (event.isCancelled || !event.player.hasPermission("elitemobs.*") ||
            event.cause !in ADMIN_TELEPORT_CAUSES || event.from.world.uid != event.to.world.uid) return null
        val match = MatchInstance.getAnyPlayerInstance(event.player) ?: return null
        return match.takeIf { matchesAdmin(event, it) }
    }

    private fun matchesAdmin(event: PlayerTeleportEvent, match: MatchInstance): Boolean =
        !event.isCancelled && event.player.hasPermission("elitemobs.*") &&
            event.cause in ADMIN_TELEPORT_CAUSES && event.from.world.uid == event.to.world.uid &&
            (match as? DungeonInstance)?.world?.uid == event.from.world.uid &&
            MatchInstance.getAnyPlayerInstance(event.player) === match

    private companion object {
        val ADMIN_TELEPORT_CAUSES = setOf(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
        )
    }
}
