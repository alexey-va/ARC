package ru.arc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.arc.util.Logging.info
import java.util.UUID

internal class SurvivalArrivalProtection(
    private val enabled: () -> Boolean,
    private val runLater: (Long, () -> Unit) -> Unit,
) : AutoCloseable {
    private class Session(val player: Player, var timed: Boolean, var awaitingActivity: Boolean)

    private val sessions = mutableMapOf<UUID, Session>()

    fun join(player: Player, ticks: Long, untilActivity: Boolean) {
        if (!enabled() || !player.isOnline || player.hasPermission(BYPASS_PERMISSION)) return

        sessions.remove(player.uniqueId)?.let(::endTimed)
        val session = Session(player, timed = !player.isInvulnerable, awaitingActivity = untilActivity)
        if (!session.timed && !session.awaitingActivity) return
        sessions[player.uniqueId] = session

        if (session.timed) {
            player.isInvulnerable = true
            info("Player {} is invulnerable", player.name)
            runLater(ticks) {
                if (sessionOf(player) === session) {
                    endTimed(session)
                    discardFinished(session)
                }
            }
        }
    }

    fun input(event: PlayerInputEvent) {
        val input = event.input
        if (input.isForward || input.isBackward || input.isLeft || input.isRight || input.isJump) {
            activity(event.player)
        }
    }

    fun interact(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) activity(event.player)
    }

    fun activity(player: Player) {
        val session = sessionOf(player) ?: return
        session.awaitingActivity = false
        discardFinished(session)
    }

    fun damage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (sessionOf(player)?.awaitingActivity != true) return
        if (!enabled() || player.hasPermission(BYPASS_PERMISSION)) {
            activity(player)
            return
        }

        // Bukkit's invulnerability flag is saved in NBT and bypassed by the void.
        if (event.cause != EntityDamageEvent.DamageCause.KILL) event.isCancelled = true
    }

    fun leave(player: Player) {
        val session = sessionOf(player) ?: return
        sessions.remove(player.uniqueId)
        endTimed(session)
    }

    override fun close() {
        sessions.values.forEach(::endTimed)
        sessions.clear()
    }

    private fun sessionOf(player: Player): Session? = sessions[player.uniqueId]?.takeIf { it.player === player }

    private fun endTimed(session: Session) {
        if (!session.timed) return
        session.timed = false
        session.player.isInvulnerable = false
        info("Player {} is not invulnerable anymore", session.player.name)
    }

    private fun discardFinished(session: Session) {
        if (!session.timed && !session.awaitingActivity) sessions.remove(session.player.uniqueId)
    }

    private companion object {
        const val BYPASS_PERMISSION = "arc.join.invulnerability.bypass"
    }
}
