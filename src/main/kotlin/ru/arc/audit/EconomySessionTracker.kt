package ru.arc.audit

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EconomySessionSnapshot(
    val sessionId: String,
    val startedAt: Long,
    val world: String?,
    val active: Boolean,
)

/** Keeps a bounded recent server session so delayed economy events retain gameplay context. */
class EconomySessionTracker(
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
    private val endedSessionRetentionMillis: Long = 5 * 60 * 1000L,
) {
    private data class State(
        val id: String,
        val startedAt: Long,
        var world: String?,
        var endedAt: Long? = null,
    )

    private val sessions = ConcurrentHashMap<UUID, State>()

    fun joined(playerId: UUID, world: String?, at: Long): EconomySessionSnapshot {
        val state = State(idProvider(), at, world?.bounded(80))
        sessions[playerId] = state
        return state.snapshot()
    }

    fun left(playerId: UUID, world: String?, at: Long) {
        sessions.computeIfPresent(playerId) { _, state ->
            state.world = world?.bounded(80) ?: state.world
            state.endedAt = at
            state
        }
    }

    fun snapshot(playerId: UUID, currentWorld: String?, at: Long): EconomySessionSnapshot? {
        val state = sessions[playerId] ?: return null
        val endedAt = state.endedAt
        if (endedAt != null && at - endedAt > endedSessionRetentionMillis) {
            sessions.remove(playerId, state)
            return null
        }
        if (endedAt == null && !currentWorld.isNullOrBlank()) state.world = currentWorld.bounded(80)
        return state.snapshot()
    }

    fun clear() = sessions.clear()

    private fun State.snapshot(): EconomySessionSnapshot = EconomySessionSnapshot(id, startedAt, world, endedAt == null)
}

internal fun String.bounded(maxLength: Int): String = trim().take(maxLength)
