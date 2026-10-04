package ru.arc.metrics.telemetry

import java.util.UUID
import kotlin.math.sqrt

internal data class JourneyPosition(val world: String, val x: Double, val y: Double, val z: Double) {
    fun distance(other: JourneyPosition): Double = if (world != other.world) 0.0 else
        sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z))
}

internal data class JourneyContext(
    val playerId: UUID,
    val playerName: String,
    val sessionId: String,
    val qa: Boolean,
    val position: JourneyPosition,
)

/** Main-thread observations, not a claim about client input or why a player left. */
internal class PlayerJourneyTracker(
    private val idleAfterMillis: Long,
    private val positionIntervalMillis: Long,
    private val emit: (JourneyContext, String, Long, Map<String, String>) -> Unit,
    private val publishContext: (UUID, JourneyContext?) -> Unit,
) {
    private data class Session(
        var context: JourneyContext,
        val startedAt: Long,
        var accountedAt: Long,
        var lastActivityAt: Long,
        var lastPositionAt: Long,
        var firstMovementAt: Long? = null,
        var activeMillis: Long = 0,
        var idleMillis: Long = 0,
        var idle: Boolean = false,
        var lastAction: String = "session.start",
        var sampledDistance: Double = 0.0,
    )
    private val sessions = mutableMapOf<UUID, Session>()

    fun join(playerId: UUID, name: String, qa: Boolean, position: JourneyPosition, now: Long, resumed: Boolean) {
        leave(playerId, now, "replaced", true)
        val context = JourneyContext(playerId, name, UUID.randomUUID().toString(), qa, position)
        sessions[playerId] = Session(context, now, now, now, now)
        publishContext(playerId, context)
        emit(context, if (resumed) "session.resume" else "session.start", now, mapOf("censoredStart" to resumed.toString()))
    }

    fun sample(playerId: UUID, position: JourneyPosition, now: Long) {
        val session = sessions[playerId] ?: return
        val distance = session.context.position.distance(position)
        account(session, now)
        updatePosition(session, position)
        if (distance >= 0.05) {
            session.sampledDistance += distance
            if (session.firstMovementAt == null) {
                session.firstMovementAt = now
                emit(session.context, "activity.first_movement", now,
                    mapOf("sinceJoinMs" to (now - session.startedAt).coerceAtLeast(0).toString()))
            }
            activity(playerId, "movement", now)
        }
        if (now - session.lastPositionAt >= positionIntervalMillis && distance >= 0.05) {
            session.lastPositionAt = now
            emit(session.context, "position.sample", now, mapOf("sampledDistance" to session.sampledDistance.toLong().toString()))
        }
    }

    /** Teleports reset the sampled position and never invent walking distance/first movement. */
    fun relocate(playerId: UUID, position: JourneyPosition, now: Long) {
        val session = sessions[playerId] ?: return
        account(session, now)
        updatePosition(session, position)
    }

    fun activity(playerId: UUID, action: String, now: Long) {
        val session = sessions[playerId] ?: return
        account(session, now)
        if (session.idle) {
            emit(session.context, "activity.resume", now,
                mapOf("idleDurationMs" to (now - session.lastActivityAt - idleAfterMillis).coerceAtLeast(0).toString(),
                    "trigger" to action))
            session.idle = false
        }
        session.lastActivityAt = maxOf(session.lastActivityAt, now)
        session.lastAction = action
    }

    fun leave(playerId: UUID, now: Long, reason: String, censored: Boolean) {
        val session = sessions.remove(playerId) ?: return
        account(session, now)
        emit(session.context, "session.end", now, buildMap {
            put("reason", reason)
            put("censored", censored.toString())
            put("durationMs", (now - session.startedAt).coerceAtLeast(0).toString())
            put("activeMs", session.activeMillis.toString())
            put("idleMs", session.idleMillis.toString())
            put("lastAction", session.lastAction)
            put("sampledDistance", session.sampledDistance.toLong().toString())
            session.firstMovementAt?.let { put("firstMovementAfterMs", (it - session.startedAt).coerceAtLeast(0).toString()) }
        })
        publishContext(playerId, null)
    }

    fun close(now: Long, reason: String) = sessions.keys.toList().forEach { leave(it, now, reason, true) }

    private fun updatePosition(session: Session, position: JourneyPosition) {
        session.context = session.context.copy(position = position)
        publishContext(session.context.playerId, session.context)
    }

    private fun account(session: Session, now: Long) {
        if (now <= session.accountedAt) return
        val idleBoundary = session.lastActivityAt + idleAfterMillis
        val active = (minOf(now, idleBoundary) - session.accountedAt).coerceAtLeast(0)
        session.activeMillis += active
        session.idleMillis += now - session.accountedAt - active
        session.accountedAt = now
        if (!session.idle && now >= idleBoundary) {
            session.idle = true
            emit(session.context, "activity.idle", idleBoundary, mapOf("thresholdMs" to idleAfterMillis.toString()))
        }
    }
}
