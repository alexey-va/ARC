package ru.arc.staffspells

import org.bukkit.Bukkit
import java.util.UUID

/** One Q and one F budget per player, shared by every staff and never reset by changing slots. */
internal class StaffHotkeyCooldowns(private val clock: () -> Long = { Bukkit.getCurrentTick().toLong() }) {
    private val deadlines = mutableMapOf<Pair<UUID, StaffHotkey>, Long>()
    private val actionDeadlines = mutableMapOf<UUID, Long>()

    fun remaining(playerId: UUID, key: StaffHotkey): Long {
        val pair = playerId to key
        val remaining = ((deadlines[pair] ?: 0) - clock()).coerceAtLeast(0)
        if (remaining == 0L) deadlines.remove(pair)
        return remaining
    }

    fun actionReady(playerId: UUID) = (actionDeadlines[playerId] ?: 0) <= clock()
    fun action(playerId: UUID) { actionDeadlines[playerId] = clock() + 4 }
    fun start(playerId: UUID, key: StaffHotkey, ticks: Long) {
        deadlines[playerId to key] = clock() + ticks.coerceAtLeast(1)
        action(playerId)
    }
    fun clear(playerId: UUID) {
        StaffHotkey.entries.forEach { deadlines.remove(playerId to it) }
        actionDeadlines.remove(playerId)
    }
    fun prune() {
        val now = clock()
        deadlines.entries.removeIf { it.value <= now }
        actionDeadlines.entries.removeIf { it.value <= now }
    }
    fun clear() { deadlines.clear(); actionDeadlines.clear() }
}

internal data class StaffHotkeyHudState(
    val qTicks: Long = 0,
    val fTicks: Long = 0,
    val continuation: String? = null,
)
