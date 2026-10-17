package ru.arc.hooks

import net.william278.huskhomes.BukkitHuskHomes
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.teleport.Teleport
import net.william278.huskhomes.user.BukkitUser
import net.william278.huskhomes.user.OnlineUser
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.util.Logging
import java.util.UUID
import kotlin.math.abs

/**
 * Observes ARC's local, fixed-position HuskHomes replays without replacing native transport.
 * HuskHomes 4.9.10 discards Paper's future: handoff is NOT success, and a deadline is NOT
 * proof of rejection. Only a matching event followed by actual arrival permits feedback.
 * All mutable state and Bukkit reads belong to the Paper main thread. Native completion
 * callbacks may arrive asynchronously and are marshalled before accessing either.
 */
internal class HuskHomesLocalTeleportObserver(
    private val scheduler: () -> TaskScheduler = { Tasks.scheduler },
    private val livePlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val resolveDestination: (Position) -> Location = { BukkitHuskHomes.Adapter.adapt(it) },
    private val timeoutTicks: () -> Long = { config.long("portal.teleport-observation.timeout-ticks", 200L).coerceIn(20L, 1200L) },
    private val notifyUnconfirmed: (Player, Boolean) -> Unit = { player, cancelled ->
        player.sendMessage(
            if (cancelled) {
                config.component("portal.teleport-observation.cancelled-message", "<yellow>Телепортация отменена.")
            } else {
                config.component(
                    "portal.teleport-observation.unconfirmed-message",
                    "<yellow>Прибытие не подтверждено. Сообщите администрации.",
                )
            },
        )
    },
    private val diagnostic: (Boolean, String) -> Unit = { warning, message ->
        if (warning) Logging.warn(message) else Logging.info(message)
    },
) : AutoCloseable {
    private val pending = mutableMapOf<UUID, Attempt>()

    @Volatile
    private var closed = false

    fun prepare(template: Teleport, player: Player, onArrival: () -> Unit): ObservedHuskTeleport? {
        val target = template.target as? Position ?: return null
        if (template.plugin.settings.crossServer.isEnabled && target.server != template.plugin.serverName) return null
        val nativeUser = template.teleporter as? BukkitUser ?: return null
        check(!closed) { "HuskHomes teleport observer is closed" }

        pending[player.uniqueId]?.let { finish(it, "SUPERSEDED", notify = false) }
        val attempt = Attempt(player, nativeUser, HomePositionSnapshot.from(target), resolveDestination(target).clone(), player.location.clone(), onArrival)
        val observed = ObservedHuskTeleport(template, { handoff(attempt) }, { failure(attempt, it) })
        attempt.teleport = observed
        pending[player.uniqueId] = attempt
        record(attempt, "STARTED")
        attempt.tasks += scheduler().runLater(timeoutTicks(), Runnable {
            if (!isCurrent(attempt)) return@Runnable
            val reason = if (attempt.eventSeen) "ARRIVAL_UNCONFIRMED" else "NO_BUKKIT_EVENT_UNKNOWN"
            finish(attempt, reason, notify = true)
        })
        return observed
    }

    fun nativeEvent(event: Cancellable, teleport: ObservedHuskTeleport) {
        val attempt = pending.values.firstOrNull { it.teleport === teleport } ?: return
        if (!isCurrent(attempt)) return
        attempt.nativeEventSeen = true
        // Read cancellation after the entire dispatch, including later MONITOR listeners.
        attempt.tasks += scheduler().runLater(1L, Runnable {
            if (isCurrent(attempt) && event.isCancelled) finish(attempt, "HUSK_CANCELLED", notify = true, cancelled = true)
        })
    }

    fun bukkitEvent(event: PlayerTeleportEvent) {
        val attempt = pending[event.player.uniqueId] ?: return
        if (!isCurrent(attempt) || event.player !== attempt.player || !attempt.nativeEventSeen) return
        // Other teleports may occur while HuskHomes loads a chunk. They do not belong to
        // this observation, including cancelled events and late superseded destinations.
        if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN || !matchesTarget(attempt.destination, event.to)) return
        attempt.eventSeen = true
        record(attempt, "BUKKIT_EVENT", "cause=${event.cause} cancelled=${event.isCancelled} from=${location(event.from)} to=${location(event.to)}")
        // Re-check the final event object next tick: later listeners may still cancel or
        // redirect an initially matching event. A matching destination confirms the observed
        // goal only; it cannot identify the discarded native future among equal targets.
        attempt.tasks += scheduler().runLater(1L, Runnable {
            if (!isCurrent(attempt)) return@Runnable
            when {
                event.isCancelled -> finish(attempt, "BUKKIT_CANCELLED", notify = true, cancelled = true)
                event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN || !matchesTarget(attempt.destination, event.to) ->
                    finish(attempt, "OTHER_OR_REDIRECTED_EVENT", notify = true)
                !arrived(event.to, attempt.player.location) -> finish(attempt, "ARRIVAL_NOT_OBSERVED", notify = true)
                else -> {
                    finish(attempt, "ARRIVAL_CONFIRMED", notify = false)
                    // This is observed arrival, not a claim about the discarded Paper future.
                    attempt.teleport.displayConfirmed(attempt.nativeUser)
                    attempt.onArrival()
                }
            }
        })
    }

    fun quit(player: Player) {
        pending[player.uniqueId]?.takeIf { it.player === player }?.let { finish(it, "DISCONNECTED", notify = false) }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.values.toList().forEach { finish(it, "SHUTDOWN", notify = false) }
    }

    private fun handoff(attempt: Attempt) {
        if (closed) return
        scheduler().runSync(Runnable {
            if (isCurrent(attempt)) record(attempt, "NATIVE_HANDOFF", "paperResult=unavailable")
        })
    }

    private fun failure(attempt: Attempt, failure: Exception) {
        if (closed) return
        scheduler().runSync(Runnable {
            if (isCurrent(attempt)) finish(attempt, "EXECUTION_EXCEPTION", notify = true, details = "exception=${failure.javaClass.name}")
        })
    }

    private fun isCurrent(attempt: Attempt): Boolean {
        if (closed || pending[attempt.player.uniqueId] !== attempt) return false
        if (livePlayer(attempt.player.uniqueId) !== attempt.player || !attempt.player.isOnline) {
            finish(attempt, "PLAYER_INSTANCE_CHANGED", notify = false)
            return false
        }
        return true
    }

    private fun finish(attempt: Attempt, outcome: String, notify: Boolean, cancelled: Boolean = false, details: String = "") {
        if (pending[attempt.player.uniqueId] !== attempt) return
        pending.remove(attempt.player.uniqueId)
        attempt.tasks.forEach(ScheduledTask::cancel)
        record(attempt, outcome, details, warning = outcome != "ARRIVAL_CONFIRMED")
        if (notify && livePlayer(attempt.player.uniqueId) === attempt.player && attempt.player.isOnline) {
            notifyUnconfirmed(attempt.player, cancelled)
        }
    }

    private fun record(attempt: Attempt, outcome: String, details: String = "", warning: Boolean = false) {
        val player = attempt.player
        val nativePlayer = attempt.nativeUser.player
        val live = livePlayer(player.uniqueId)
        diagnostic(
            warning,
            "husk_portal_teleport op=${attempt.id} outcome=$outcome elapsedMs=${(System.nanoTime() - attempt.startedNanos) / 1_000_000} player=${player.name} uuid=${player.uniqueId}" +
                " from=${location(attempt.from)} target=${attempt.target.server}/${attempt.target.world}:${attempt.target.x},${attempt.target.y},${attempt.target.z} resolvedTarget=${location(attempt.destination)}" +
                " current=${location(player.location)} nativeCurrent=${location(nativePlayer.location)} nativeSameAsPassed=${nativePlayer === player} nativeSameAsLive=${nativePlayer === live}" +
                " passedSameAsLive=${player === live} state=${state(player)} nativeState=${state(nativePlayer)}" +
                " huskEvent=${attempt.nativeEventSeen} bukkitEvent=${attempt.eventSeen} $details",
        )
    }

    private class Attempt(
        val player: Player,
        val nativeUser: BukkitUser,
        val target: HomePositionSnapshot,
        val destination: Location,
        val from: Location,
        val onArrival: () -> Unit,
    ) {
        val id: UUID = UUID.randomUUID()
        val startedNanos = System.nanoTime()
        val tasks = mutableListOf<ScheduledTask>()
        lateinit var teleport: ObservedHuskTeleport
        var nativeEventSeen = false
        var eventSeen = false
    }

    companion object {
        private val config get() = ConfigManager.ofModule(ARC.instance.dataPath, "misc.yml")

        private fun matchesTarget(target: Location, location: Location?): Boolean =
            location != null && target.world != null && target.world?.uid == location.world?.uid &&
                abs(target.x - location.x) < 0.000_001 && abs(target.y - location.y) < 0.000_001 && abs(target.z - location.z) < 0.000_001

        private fun arrived(target: Location?, current: Location): Boolean =
            target != null && target.world?.uid == current.world?.uid && target.distanceSquared(current) <= 1.0

        private fun location(location: Location?): String =
            location?.let { "${it.world?.name}[${it.world?.uid}]:${it.x},${it.y},${it.z}" } ?: "null"

        private fun state(player: Player): String =
            "online:${player.isOnline},valid:${player.isValid},dead:${player.isDead},sleeping:${player.isSleeping}," +
                "vehicle:${player.vehicle?.let { "${it.type}:${it.uniqueId}" }}," +
                "passengers:${player.passengers.size}:${player.passengers.take(8).joinToString(",") { "${it.type}:${it.uniqueId}" }}"
    }
}

/** Keeps the native user, validation, events, actions and transport; only gates success feedback. */
internal class ObservedHuskTeleport(
    original: Teleport,
    private val onHandoff: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Teleport(
    original.executor,
    original.teleporter,
    original.target,
    original.type,
    false,
    original.actions,
    original.plugin,
) {
    override fun displayTeleportingComplete(user: OnlineUser) = onHandoff()

    fun displayConfirmed(user: OnlineUser) = super.displayTeleportingComplete(user)

    override fun execute() {
        try {
            super.execute()
        } catch (failure: Exception) {
            onFailure(failure)
            throw failure
        }
    }
}
