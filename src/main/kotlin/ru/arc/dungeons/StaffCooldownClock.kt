package ru.arc.dungeons

import ru.arc.dungeons.api.ArcStaffCooldowns
import java.util.UUID

/** Main-thread staff deadlines. The spell owner reads active budgets and clears them on shutdown. */
internal class StaffCooldownClock(private val currentTick: () -> Int) : ArcStaffCooldowns {
    private val deadlines = mutableMapOf<UUID, Long>()
    private var previousTick = currentTick()
    private var elapsedTicks = 0L

    override fun remainingTicks(player: UUID): Long {
        val deadline = deadlines[player] ?: return 0L
        val remaining = (deadline - now()).coerceAtLeast(0L)
        if (remaining == 0L) deadlines.remove(player)
        return remaining
    }

    override fun start(player: UUID, ticks: Long) {
        if (ticks <= 0L) clear(player) else deadlines[player] = now() + ticks
    }

    override fun clear(player: UUID) {
        deadlines.remove(player)
    }

    private fun now(): Long {
        val tick = currentTick()
        // Bukkit's signed Int counter wraps; elapsed server ticks remain unsigned.
        elapsedTicks += (tick - previousTick).toUInt().toLong()
        previousTick = tick
        return elapsedTicks
    }
}
