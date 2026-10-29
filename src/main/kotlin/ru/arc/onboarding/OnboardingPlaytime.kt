package ru.arc.onboarding

import ru.arc.network.BackendServerId
import java.util.Locale

internal data class OnboardingServerTicks(val baseline: Int, val maximum: Int) {
    init { require(baseline >= 0 && maximum >= baseline) { "Invalid onboarding playtime counter" } }
}

/**
 * Native PLAY_ONE_MINUTE is tick-counted PLAY_TIME, not wall-clock minutes.
 * Each backend has its own persisted baseline; reconnects and server restarts
 * retain it. BUILD's optional invitation starts once in the same tick budget;
 * an actual construction keeps the normal overall allowance.
 * The caller captures counters on Paper and serializes its samples.
 * A reset/rollback/overflow fails closed instead of decreasing elapsed time or
 * silently granting a new three-hour allowance; no player statistic is changed.
 */
internal data class OnboardingPlaytime private constructor(
    val servers: Map<String, OnboardingServerTicks>,
    val buildSuggestedAtTicks: Long? = null,
) {
    class CounterMovedBackwards(val serverId: String, val previousCounter: Int, val counter: Int) :
        IllegalArgumentException("Native playtime counter moved backwards on $serverId: $previousCounter -> $counter")

    val playedTicks: Long get() = servers.values.sumOf { it.maximum.toLong() - it.baseline }
    val remainingTicks: Long get() = (LIMIT_TICKS - playedTicks).coerceAtLeast(0)
    val expired: Boolean get() = playedTicks >= LIMIT_TICKS

    fun remainingTicks(awaitingBuild: Boolean): Long =
        if (awaitingBuild) minOf(remainingTicks, (BUILD_SUGGESTION_TICKS - (playedTicks - (buildSuggestedAtTicks ?: playedTicks))).coerceAtLeast(0))
        else remainingTicks

    fun sample(serverId: String, counter: Int, suggestBuild: Boolean = false): OnboardingPlaytime {
        val server = BackendServerId.of(serverId.trim().lowercase(Locale.ROOT)).value
        val previous = servers[server]
        val maximum = previous?.maximum ?: 0
        if (counter < maximum) throw CounterMovedBackwards(server, maximum, counter)
        val sampled = create(servers + (server to OnboardingServerTicks(previous?.baseline ?: counter, counter)), buildSuggestedAtTicks)
        return if (suggestBuild && buildSuggestedAtTicks == null) create(sampled.servers, sampled.playedTicks) else sampled
    }

    companion object {
        const val LIMIT_TICKS = 216_000L
        const val BUILD_SUGGESTION_TICKS = 18_000L
        const val MAX_SERVERS = 16

        fun empty() = OnboardingPlaytime(emptyMap())

        fun create(servers: Map<String, OnboardingServerTicks>, buildSuggestedAtTicks: Long? = null): OnboardingPlaytime {
            require(servers.size <= MAX_SERVERS) { "Too many onboarding playtime backends" }
            servers.keys.forEach { BackendServerId.of(it) }
            val playedTicks = servers.values.sumOf { it.maximum.toLong() - it.baseline }
            require(buildSuggestedAtTicks == null || buildSuggestedAtTicks in 0L..playedTicks) { "Invalid onboarding build suggestion start" }
            return OnboardingPlaytime(servers.toMap(), buildSuggestedAtTicks)
        }
    }
}
