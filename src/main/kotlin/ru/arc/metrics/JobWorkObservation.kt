package ru.arc.metrics

import java.time.Instant
import java.time.ZoneId

/** Observed time between accepted EcoJobs XP events, never a claim of human attention or session time. */
data class JobWorkObservation(
    val job: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    fun valid(): Boolean = job in JOBS && startedAt > 0 && endedAt >= startedAt &&
        endedAt - startedAt <= MAX_GAP_MILLIS

    /** Split before persistence so midnight and a measurement reset cannot move old time into a new day. */
    fun days(zone: ZoneId, boundaryAt: Long): List<JobWorkDay> {
        require(valid())
        if (endedAt < boundaryAt) return emptyList()
        var start = maxOf(startedAt, boundaryAt)
        val result = mutableListOf<JobWorkDay>()
        while (start < endedAt) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(endedAt, midnight)
            result += JobWorkDay(date.toString(), start, end, observations = 0)
            start = end
        }
        // The accepted action belongs to its own day, including an action exactly at midnight.
        val actionDay = Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate().toString()
        if (result.lastOrNull()?.date == actionDay) {
            result[result.lastIndex] = result.last().copy(observations = 1)
        } else {
            result += JobWorkDay(actionDay, endedAt, endedAt, observations = 1)
        }
        return result
    }

    companion object {
        const val MAX_GAP_MILLIS = 30_000L
        val JOBS = setOf("miner", "builder", "farmer", "lumberjack", "enchanter", "toolsmith", "smelter",
            "slayer", "fisherman", "beekeeper", "explorer")
    }
}

/** Half-open interval [startedAt, endedAt); observations count the accepted endpoint separately. */
data class JobWorkDay(val date: String, val startedAt: Long, val endedAt: Long, val observations: Long)

/** Local session state only; the product store owns durable totals. Call breakContinuity on AFK/quit. */
internal class JobWorkClock(private val maxCursors: Int = 8_192) {
    init { require(maxCursors in 1..8_192) }
    var capacityEvictions: Long = 0; private set
    var clockRegressions: Long = 0; private set
    private data class Key(val player: String, val job: String)
    private data class Cursor(var lastAction: Long, var lastEmission: Long)
    private val cursors = LinkedHashMap<Key, Cursor>(16, .75f, true)

    /** At most one observation per second per profession. A skipped sample is not a dropped action count. */
    fun action(player: String, job: String, now: Long): JobWorkObservation? {
        require(player.matches(Regex("[a-f0-9]{64}")))
        require(job in JobWorkObservation.JOBS && now > 0)
        val key = Key(player, job)
        val previous = cursors[key]
        if (previous != null && now < previous.lastAction) {
            clockRegressions++
            // Reject a clock rollback until the last accepted timestamp has been reached again.
            return null
        }
        val start = if (previous != null && now - previous.lastAction <= JobWorkObservation.MAX_GAP_MILLIS &&
            now - previous.lastEmission <= JobWorkObservation.MAX_GAP_MILLIS) previous.lastEmission else now
        if (previous != null) {
            previous.lastAction = now
            if (now - previous.lastEmission < MIN_SAMPLE_MILLIS) return null
        }
        cursors[key] = Cursor(now, now)
        if (cursors.size > maxCursors) {
            cursors.remove(cursors.keys.first())
            capacityEvictions++
        }
        return JobWorkObservation(job, start, now)
    }

    fun breakContinuity(player: String) { cursors.keys.removeIf { it.player == player } }
    fun clear() { cursors.clear(); capacityEvictions = 0; clockRegressions = 0 }

    companion object {
        const val MIN_SAMPLE_MILLIS = 1_000L
    }
}

/** Monotonic union of one player's profession intervals within one calendar day. */
data class JobWorkTotals(
    var firstObservedAt: Long = 0,
    var observedMillis: Long = 0,
    var observations: Long = 0,
    var lastEndedAt: Long = 0,
    var lateObservations: Long = 0,
) {
    fun add(day: JobWorkDay) {
        require(day.startedAt >= 0 && day.endedAt >= day.startedAt && day.observations in 0..1)
        if (firstObservedAt == 0L) firstObservedAt = day.startedAt
        if (day.endedAt <= lastEndedAt) {
            // ponytail: bounded watermark union; late unseen prefixes are omitted and reported, never overcounted.
            lateObservations = (lateObservations + 1).coerceAtMost(MAX_OBSERVATIONS)
            return
        }
        observedMillis = (observedMillis + day.endedAt - maxOf(day.startedAt, lastEndedAt)).coerceAtMost(MAX_DAY_MILLIS)
        observations = (observations + day.observations).coerceAtMost(MAX_OBSERVATIONS)
        lastEndedAt = day.endedAt
    }

    fun valid(): Boolean = observedMillis in 0..MAX_DAY_MILLIS && observations in 0..MAX_OBSERVATIONS &&
        firstObservedAt > 0 && lastEndedAt >= firstObservedAt && lateObservations in 0..MAX_OBSERVATIONS

    companion object {
        // Includes a 25-hour DST calendar day; timestamps, not fixed 24-hour arithmetic, split intervals.
        const val MAX_DAY_MILLIS = 90_000_000L
        const val MAX_OBSERVATIONS = 1_000_000L
    }
}
