package ru.arc.staffspells

import org.bukkit.Bukkit
import java.util.UUID

internal enum class StaffAbility { PRIMARY, SECONDARY, Q, F;
    companion object {
        fun attack(secondary: Boolean) = if (secondary) SECONDARY else PRIMARY
        fun hotkey(key: StaffHotkey) = if (key == StaffHotkey.Q) Q else F
    }
}

/** Each ability shares one player budget across every staff, slot and item instance. */
internal class StaffHotkeyCooldowns(private val clock: () -> Long = { Bukkit.getCurrentTick().toLong() }) {
    private data class Key(val player: UUID, val ability: StaffAbility)
    private data class Deadline(val end: Long, val duration: Long)
    private val deadlines = mutableMapOf<Key, Deadline>()
    private var previousTick = clock()
    private var elapsedTicks = 0L

    private fun now(): Long {
        val tick = clock()
        elapsedTicks += (tick - previousTick) and 0xffffffffL
        previousTick = tick
        return elapsedTicks
    }

    fun remaining(playerId: UUID, ability: StaffAbility): Long {
        val key = Key(playerId, ability)
        val remaining = ((deadlines[key]?.end ?: 0) - now()).coerceAtLeast(0)
        if (remaining == 0L) deadlines.remove(key)
        return remaining
    }

    fun total(playerId: UUID, ability: StaffAbility): Long =
        deadlines[Key(playerId, ability)]?.duration ?: 0L

    fun start(playerId: UUID, ability: StaffAbility, ticks: Long) {
        val duration = ticks.coerceAtLeast(1)
        deadlines[Key(playerId, ability)] = Deadline(now() + duration, duration)
    }
    fun clear(playerId: UUID) { deadlines.keys.removeIf { it.player == playerId } }
    fun prune() {
        val now = now()
        deadlines.entries.removeIf { it.value.end <= now }
    }
    fun clear() { deadlines.clear() }
}

internal data class StaffHotkeyHudState(
    val qTicks: Long = 0,
    val fTicks: Long = 0,
    val continuation: String? = null,
)
