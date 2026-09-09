package ru.arc.onboarding

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.land.Land
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.ARC
import ru.arc.lands.trustedPlayerIds
import ru.arc.paper.audience.NativePaperAudienceEffects as effects
import java.util.UUID

internal enum class ClaimBoundaryNotice {
    NONE,
    ENTER,
    LEAVE,
}

internal data class ClaimBoundarySnapshot(
    val world: UUID,
    val landId: String?,
    val landName: String?,
    val friendly: Boolean,
)

internal fun claimBoundaryNotice(
    from: ClaimBoundarySnapshot,
    to: ClaimBoundarySnapshot,
): ClaimBoundaryNotice = when {
    from.world != to.world -> ClaimBoundaryNotice.NONE
    from.landId == null && to.landId != null -> ClaimBoundaryNotice.ENTER
    from.landId != null && to.landId == null && from.friendly -> ClaimBoundaryNotice.LEAVE
    else -> ClaimBoundaryNotice.NONE
}

/** Short, transition-only Lands hints; lookups never load chunks. */
internal class ClaimBoundaryListener(
    private val config: OnboardingConfig,
) : Listener {
    private val integration = LandsIntegration.of(ARC.instance)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        val from = event.from
        val to = event.to ?: return
        if (from.world?.uid != to.world?.uid ||
            (from.blockX shr 4 == to.blockX shr 4 && from.blockZ shr 4 == to.blockZ shr 4)
        ) return
        observe(event.player, from, to)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.world?.uid != to.world?.uid ||
            (from.blockX shr 4 == to.blockX shr 4 && from.blockZ shr 4 == to.blockZ shr 4)
        ) return
        observe(event.player, from, to)
    }

    private fun observe(player: Player, from: Location, to: Location) {
        if (OnboardingModule.claimGuide?.hasHologram(player) == true ||
            ru.arc.landsui.RegionToolItem.matches(player.inventory.itemInMainHand)) return
        val world = to.world ?: return
        if (!config.allowsWorld(world.name) || integration.getWorld(world) == null) return
        val previous = snapshot(player, from)
        val current = snapshot(player, to)
        when (claimBoundaryNotice(previous, current)) {
            ClaimBoundaryNotice.ENTER -> current.landName?.let { name ->
                effects.sendActionBar(player, claimGuideLandText(config.claimText("boundary-enter"), name))
            }
            ClaimBoundaryNotice.LEAVE -> previous.landName?.let { name ->
                effects.sendActionBar(player, claimGuideLandText(config.claimText("boundary-leave"), name))
            }
            ClaimBoundaryNotice.NONE -> Unit
        }
    }

    private fun snapshot(player: Player, location: Location): ClaimBoundarySnapshot {
        val world = requireNotNull(location.world)
        val land = integration.getLandByUnloadedChunk(world, location.blockX shr 4, location.blockZ shr 4)
        return ClaimBoundarySnapshot(
            world = world.uid,
            landId = land?.ulid?.toString(),
            landName = land?.displayName(),
            friendly = land?.isFriendlyTo(player.uniqueId) == true,
        )
    }

    private fun Land.isFriendlyTo(playerId: UUID): Boolean =
        ownerUID == playerId || playerId in trustedPlayerIds()

    private fun Land.displayName(): String = name.trim().take(24).ifBlank { "участок" }
}
