package ru.arc.metrics.telemetry

import ru.arc.telemetry.PlayerTelemetryEvent
import java.util.UUID

/** Paper-thread buffer for native actions. The sink admits detached rows to the asynchronous store. */
internal class PlayerTelemetryActionBuffer(
    private val server: String,
    private val capacity: Int = 512,
    private val pendingSince: (Long?) -> Unit = {},
    private val emit: (() -> PlayerTelemetryEvent) -> Unit,
) {
    private data class Key(
        val playerId: UUID,
        val playerName: String,
        val sessionId: String,
        val qa: Boolean,
        val world: String,
        val event: String,
        val subject: String?,
        val attributes: Map<String, String>,
    )

    private class Pending(val firstAt: Long, var lastAt: Long = firstAt, var count: Int = 1)

    private val groups = linkedMapOf<Key, Pending>()
    private var window: Long? = null

    init { require(capacity > 0) }

    fun add(context: JourneyContext, world: String, event: String, subject: String?, attributes: Map<String, String>, at: Long) {
        val currentWindow = windowStart(at)
        val previousWindow = window
        if (currentWindow != previousWindow) {
            if (previousWindow == null || currentWindow < previousWindow) pendingSince(currentWindow)
            // Leave the earlier watermark in place until old rows have been offered.
            flush { true }
            if (previousWindow != null && currentWindow > previousWindow) pendingSince(currentWindow)
            window = currentWindow
        }
        val key = Key(context.playerId, context.playerName, context.sessionId, context.qa, world, event, subject, attributes.toMap())
        val current = groups[key]
        if (current != null) {
            current.lastAt = maxOf(current.lastAt, at)
            current.count++
            if (current.count == Int.MAX_VALUE) {
                groups.remove(key)
                deliver(key, current)
                clearWatermarkIfEmpty()
            }
            return
        }
        if (groups.size == capacity) {
            val (oldestKey, oldest) = groups.entries.first()
            groups.remove(oldestKey)
            deliver(oldestKey, oldest)
        }
        groups[key] = Pending(at)
    }

    fun flushExpired(at: Long) {
        if (windowStart(at) != window) flushAll()
    }

    fun flushPlayer(playerId: UUID) {
        flush { it.playerId == playerId }
        clearWatermarkIfEmpty()
    }

    fun flushAll() {
        flush { true }
        clearWatermarkIfEmpty()
    }

    private fun clearWatermarkIfEmpty() {
        if (groups.isEmpty() && window != null) {
            window = null
            pendingSince(null)
        }
    }

    private fun windowStart(at: Long): Long = (at / WINDOW_MILLIS * WINDOW_MILLIS).coerceAtLeast(1L)

    private inline fun flush(matches: (Key) -> Boolean) {
        val pending = groups.entries.iterator()
        while (pending.hasNext()) {
            val (key, group) = pending.next()
            if (matches(key)) {
                pending.remove()
                deliver(key, group)
            }
        }
    }

    private fun deliver(key: Key, group: Pending) = emit {
        PlayerTelemetryEvent(
            occurredAt = group.firstAt,
            server = server,
            playerId = key.playerId.toString(),
            playerName = key.playerName,
            sessionId = key.sessionId,
            source = "player",
            event = key.event,
            subject = key.subject,
            world = key.world,
            qa = key.qa,
            attributes = buildMap(key.attributes.size + 2) {
                putAll(key.attributes)
                put("count", group.count.toString())
                if (group.count > 1) put("lastAtMs", group.lastAt.toString())
            },
        )
    }

    companion object {
        private const val WINDOW_MILLIS = 5_000L
        val EVENTS = setOf("interact", "block.break", "block.place", "inventory.click")
    }
}
