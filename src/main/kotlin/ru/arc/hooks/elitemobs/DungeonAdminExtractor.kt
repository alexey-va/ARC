package ru.arc.hooks.elitemobs

import com.Zrips.CMI.Modules.Teleportations.CMITeleportType
import com.Zrips.CMI.events.CMIAsyncPlayerTeleportEvent
import com.magmaguy.elitemobs.instanced.MatchInstance
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Ends native EliteMobs participation when an authorized admin pulls a player with CMI /tphere.
 *
 * CMI announces the operation asynchronously, so this listener only records it there. The actual
 * EliteMobs mutation happens at the synchronous Bukkit teleport boundary, before EliteMobs' own
 * teleport guard gets a chance to reject the move out of the instance.
 */
internal class DungeonAdminExtractor(
    private val onlinePlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val matchFor: (Player) -> MatchInstance? = MatchInstance::getAnyPlayerInstance,
    private val clock: () -> Long = System::nanoTime,
) : Listener {
    private val pending = ConcurrentHashMap<UUID, PendingExtraction>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun prepare(event: CMIAsyncPlayerTeleportEvent) {
        if (event.type != CMITeleportType.TpHere || event.to == null) return
        val actor = event.sender as? Player ?: return
        val target = event.player ?: return
        if (actor.uniqueId == target.uniqueId) return

        val now = clock()
        pending.entries.removeIf { it.value.expiresAtNanos <= now }
        if (pending.size >= MAX_PENDING && !pending.containsKey(target.uniqueId)) return
        pending[target.uniqueId] = PendingExtraction(actor.uniqueId, now + REQUEST_TTL_NANOS)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun extract(event: PlayerTeleportEvent) {
        val request = pending.remove(event.player.uniqueId) ?: return
        if (event.cause != PlayerTeleportEvent.TeleportCause.COMMAND) return
        if (request.expiresAtNanos <= clock()) return

        val actor = onlinePlayer(request.actorId) ?: return
        if (!actor.hasPermission(PERMISSION)) return
        matchFor(event.player)?.removeAnyKind(event.player)
    }

    @EventHandler
    fun forget(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        pending.remove(playerId)
        pending.entries.removeIf { it.value.actorId == playerId }
    }

    private data class PendingExtraction(
        val actorId: UUID,
        val expiresAtNanos: Long,
    )

    private companion object {
        const val PERMISSION = "arc.dungeon.admin.extract"
        const val MAX_PENDING = 128
        const val REQUEST_TTL_NANOS = 15_000_000_000L
    }
}
