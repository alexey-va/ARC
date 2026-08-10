package ru.arc.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.arc.config.Config
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Bounded Paper-world classification for product-level dungeon metrics. */
data class DungeonInterestConfig(
    val enabled: Boolean = true,
    val worldGlobs: List<String> = DEFAULT_WORLD_GLOBS,
    val excludedWorlds: Set<String> = DEFAULT_EXCLUDED_WORLDS,
    val maxTrackedWorlds: Int = 128,
) {
    private val normalizedExclusions =
        excludedWorlds
            .asSequence()
            .map(::normalizeWorldName)
            .filter(String::isNotEmpty)
            .take(MAX_CONFIG_ENTRIES)
            .toSet()
    private val worldPatterns =
        worldGlobs
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it.length <= MAX_WORLD_NAME_LENGTH && GLOB_PATTERN.matches(it) }
            .take(MAX_CONFIG_ENTRIES)
            .map(::globRegex)
            .toList()

    fun dungeonWorld(worldName: String): String? {
        if (!enabled) return null
        val normalized = normalizeWorldName(worldName)
        if (!WORLD_LABEL.matches(normalized) || normalized in normalizedExclusions) return null
        return normalized.takeIf { worldPatterns.any { pattern -> pattern.matches(normalized) } }
    }

    companion object {
        private const val MAX_CONFIG_ENTRIES = 32
        private const val MAX_WORLD_NAME_LENGTH = 64
        private val WORLD_LABEL = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
        private val GLOB_PATTERN = Regex("[a-z0-9_.*-]+")
        val DEFAULT_WORLD_GLOBS = listOf("em_*", "spn_*", "otd_dungeon")
        val DEFAULT_EXCLUDED_WORLDS = setOf("em_adventurers_guild")

        fun from(config: Config): DungeonInterestConfig =
            DungeonInterestConfig(
                enabled = config.bool("dungeon-interest.enabled", true),
                worldGlobs =
                    config
                        .stringList("dungeon-interest.world-globs", DEFAULT_WORLD_GLOBS)
                        .take(MAX_CONFIG_ENTRIES),
                excludedWorlds =
                    config
                        .stringList("dungeon-interest.excluded-worlds", DEFAULT_EXCLUDED_WORLDS.toList())
                        .take(MAX_CONFIG_ENTRIES)
                        .toSet(),
                maxTrackedWorlds =
                    config
                        .integer("dungeon-interest.max-tracked-worlds", 128)
                        .coerceIn(1, 256),
            )

        private fun normalizeWorldName(value: String): String = value.trim().lowercase(Locale.ROOT)

        private fun globRegex(glob: String): Regex =
            Regex(
                glob
                    .split('*')
                    .joinToString(separator = ".*", prefix = "^", postfix = "$") { Regex.escape(it) },
            )
    }
}

/**
 * Counts aggregate dungeon interest without exporting player identity.
 *
 * All calls are made on the Paper server thread. Player IDs exist only as
 * ephemeral session keys and never become metric labels or persisted data.
 */
class DungeonInterestMetrics(
    private val registry: MeterRegistry,
    private val config: DungeonInterestConfig,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Session(
        val world: String,
        val startedAtNanos: Long,
        var accountedAtNanos: Long,
    )

    private data class WorldMeters(
        val playerTime: Counter,
        val visits: Counter,
        val sessionDuration: Timer,
    )

    private val sessions = mutableMapOf<String, Session>()
    private val meters = linkedMapOf<String, WorldMeters>()

    fun registerWorld(worldName: String): Boolean = metersFor(worldName) != null

    /** Seed an already-online player after module init/reload without inventing a visit. */
    fun trackExisting(
        playerId: String,
        worldName: String,
    ) {
        startSession(playerId, worldName, countVisit = false)
    }

    /** Record a join or a world transition. */
    fun enter(
        playerId: String,
        worldName: String,
    ) {
        startSession(playerId, worldName, countVisit = true)
    }

    /** Record an organic quit. */
    fun leave(playerId: String) {
        finishSession(playerId, nanoTime(), recordDuration = true)
    }

    /** Flush active player-seconds so long visits are visible before the player leaves. */
    fun sample() {
        val now = nanoTime()
        sessions.values.forEach { session -> flushPlayerTime(session, now) }
    }

    /** Flush censored active time during module shutdown without claiming a completed visit. */
    fun shutdown() {
        val now = nanoTime()
        sessions.values.forEach { session -> flushPlayerTime(session, now) }
        sessions.clear()
    }

    private fun startSession(
        playerId: String,
        worldName: String,
        countVisit: Boolean,
    ) {
        val now = nanoTime()
        val world = config.dungeonWorld(worldName)
        val current = sessions[playerId]
        if (current?.world == world && world != null) return

        finishSession(playerId, now, recordDuration = true)
        if (world == null) return
        val worldMeters = metersFor(world) ?: return
        sessions[playerId] = Session(world, now, now)
        if (countVisit) worldMeters.visits.increment()
    }

    private fun finishSession(
        playerId: String,
        now: Long,
        recordDuration: Boolean,
    ) {
        val session = sessions.remove(playerId) ?: return
        flushPlayerTime(session, now)
        val duration = (now - session.startedAtNanos).coerceAtLeast(0L)
        if (recordDuration && duration > 0L) {
            meters[session.world]?.sessionDuration?.record(duration, TimeUnit.NANOSECONDS)
        }
    }

    private fun flushPlayerTime(
        session: Session,
        now: Long,
    ) {
        val elapsed = now - session.accountedAtNanos
        if (elapsed <= 0L) return
        meters[session.world]?.playerTime?.increment(elapsed / NANOS_PER_SECOND)
        session.accountedAtNanos = now
    }

    private fun metersFor(rawWorldName: String): WorldMeters? {
        val world = config.dungeonWorld(rawWorldName) ?: return null
        meters[world]?.let { return it }
        if (meters.size >= config.maxTrackedWorlds) return null

        return WorldMeters(
            playerTime =
                Counter
                    .builder("arc_dungeon_player_time")
                    .description("Observed aggregate player time in dungeon worlds")
                    .baseUnit("seconds")
                    .tag("world", world)
                    .register(registry),
            visits =
                Counter
                    .builder("arc_dungeon_visits")
                    .description("Observed player entries into dungeon worlds")
                    .tag("world", world)
                    .register(registry),
            sessionDuration =
                Timer
                    .builder("arc_dungeon_session_duration")
                    .description("Observed duration of completed dungeon-world visits")
                    .tag("world", world)
                    .register(registry),
        ).also { meters[world] = it }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
