package ru.arc.onboarding

import ru.arc.network.BackendServerId
import java.util.Locale

internal data class OnboardingServerTicks(val baseline: Int, val maximum: Int) {
    init { require(baseline >= 0 && maximum >= baseline) { "Invalid onboarding playtime counter" } }
}

/**
 * Native PLAY_ONE_MINUTE is tick-counted PLAY_TIME, not wall-clock minutes.
 * Each backend has its own persisted baseline; reconnects and server restarts
 * retain it. The caller captures counters on Paper and serializes its samples.
 * A reset/rollback/overflow fails closed instead of decreasing elapsed time or
 * silently granting a new three-hour allowance; no player statistic is changed.
 */
internal data class OnboardingPlaytime private constructor(val servers: Map<String, OnboardingServerTicks>) {
    class CounterMovedBackwards(val serverId: String, val previousCounter: Int, val counter: Int) :
        IllegalArgumentException("Native playtime counter moved backwards on $serverId: $previousCounter -> $counter")

    val playedTicks: Long get() = servers.values.sumOf { it.maximum.toLong() - it.baseline }
    val remainingTicks: Long get() = (LIMIT_TICKS - playedTicks).coerceAtLeast(0)
    val expired: Boolean get() = playedTicks >= LIMIT_TICKS

    fun sample(serverId: String, counter: Int): OnboardingPlaytime {
        val server = BackendServerId.of(serverId.trim().lowercase(Locale.ROOT)).value
        val previous = servers[server]
        val maximum = previous?.maximum ?: 0
        if (counter < maximum) throw CounterMovedBackwards(server, maximum, counter)
        return create(servers + (server to OnboardingServerTicks(previous?.baseline ?: counter, counter)))
    }

    companion object {
        const val LIMIT_TICKS = 216_000L
        const val MAX_SERVERS = 16

        fun empty() = OnboardingPlaytime(emptyMap())

        fun create(servers: Map<String, OnboardingServerTicks>): OnboardingPlaytime {
            require(servers.size <= MAX_SERVERS) { "Too many onboarding playtime backends" }
            servers.keys.forEach { BackendServerId.of(it) }
            return OnboardingPlaytime(servers.toMap())
        }
    }
}
