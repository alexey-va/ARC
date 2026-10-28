package ru.arc.landsui

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.player.invite.Invite
import me.angeschossen.lands.api.player.LandPlayer
import me.angeschossen.lands.api.memberholder.CMDTarget
import org.bukkit.entity.Player
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class LandsUiInviteIntent { TRUST, OWNER, MERGE }

data class LandsUiInvitation(
    val landId: String,
    val landName: String,
    val senderId: UUID,
    val createdAt: Long,
    val intent: LandsUiInviteIntent,
)

internal class LandsUiInvitations(private val integration: LandsIntegration) {
    fun list(player: Player): List<LandsUiInvitation> {
        if (!player.hasPermission("lands.command.invites.menu")) return emptyList()
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return emptyList()
        return snapshots(player.uniqueId, landPlayer)
    }

    fun respond(player: Player, expected: LandsUiInvitation, accept: Boolean): CompletableFuture<LandsUiChangeResult> {
        val permission = if (accept) "lands.command.invites.accept" else "lands.command.invites.decline"
        if (!player.hasPermission(permission)) return completed(LandsUiChangeResult.DENIED)
        val landPlayer = integration.getLandPlayer(player.uniqueId) ?: return completed(LandsUiChangeResult.NOT_FOUND)
        if (expected !in snapshots(player.uniqueId, landPlayer)) return completed(LandsUiChangeResult.STALE)
        val landToken = CMDTarget.parseCMDName(expected.landName)
        if (landToken.isEmpty() || landToken.length > 128 ||
            landToken.any { it.isWhitespace() || Character.isISOControl(it) }) {
            return completed(LandsUiChangeResult.INVALID)
        }
        return try {
            // The native command also applies trust-owner, player limits and bans before accept/deny.
            val action = if (accept) "accept" else "decline"
            completed(if (player.performCommand("lands invites $action $landToken")) LandsUiChangeResult.DISPATCHED
                else LandsUiChangeResult.REJECTED)
        } catch (failure: Exception) {
            warn("Lands invitation response failed for {} in {}", player.uniqueId, expected.landId, failure)
            completed(LandsUiChangeResult.REJECTED)
        }
    }

    private fun snapshots(viewerId: UUID, landPlayer: LandPlayer): List<LandsUiInvitation> =
        landPlayer.invites.asSequence().filter(Invite::class.java::isInstance).mapNotNull { value ->
            // Generic signatures are stripped from the deployed JAR; Class.cast supports both API shapes.
            val invite = Invite::class.java.cast(value)
            if (invite.receiver != viewerId || !invite.land.exists()) return@mapNotNull null
            val intent = LandsUiInviteIntent.entries.firstOrNull { it.name == invite.intent.name } ?: return@mapNotNull null
            LandsUiInvitation(invite.land.ulid.toString(), invite.land.name, invite.sender, invite.time, intent)
        }.sortedBy { it.landName.lowercase(java.util.Locale.ROOT) }.toList()

    private fun completed(result: LandsUiChangeResult) = CompletableFuture.completedFuture(result)
}
