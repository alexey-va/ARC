package ru.arc.metrics

import ru.arc.redis.safety.MessageClaimResult
import ru.arc.redis.safety.RecentMessageDeduplicator
import java.util.UUID

/** Narrow optional entry point for Paper plugins with a soft dependency on ARC. */
object ExternalProductTelemetryBridge {
    private const val MAX_OPERATION_ID = 80
    private const val DEDUPE_TTL_MILLIS = 24 * 60 * 60 * 1_000L
    private val acceptedSources = setOf("arcecojobs", "arcfarms", "arcvotes", "arcranks", "arcbuilder", "arcduels", "arcevents", "arcgiveaways", "trails")
    private val operationPattern = Regex("[A-Za-z0-9_.:-]{1,$MAX_OPERATION_ID}")
    private val rawEventOperationPattern = Regex("[A-Za-z0-9_.:-]{1,256}")

    @Volatile private var replayGuard = RecentMessageDeduplicator(DEDUPE_TTL_MILLIS, 4_096)
    @Volatile private var eventReplayGuard = RecentMessageDeduplicator(DEDUPE_TTL_MILLIS, 4_096)

    @JvmStatic
    fun record(playerId: UUID, source: String, feature: String? = null, outcome: String? = null, action: String? = null, operationId: String): Boolean =
        validateAndRecord(playerId, source, feature, outcome, action, operationId) { id, f, o, a -> MetricsModule.recordExternalProduct(id, f, o, a) }

    @JvmStatic
    fun recordEvent(playerId: UUID, source: String, event: String, operationId: String): Boolean {
        val parsedSource = ExternalProductSource.entries.firstOrNull { it.label == source.trim().lowercase() } ?: return false
        val parsedEvent = ExternalProductEvent.entries.firstOrNull { it.label == event.trim().lowercase() } ?: return false
        if (parsedEvent.source != parsedSource || !rawEventOperationPattern.matches(operationId)) return false
        val wireOperationId = ProductPseudonym.of(operationId)
        val replayKey = ProductPseudonym.of("event|$playerId|${parsedSource.label}|${parsedEvent.label}|$wireOperationId")
        if (eventReplayGuard.claim(replayKey, System.currentTimeMillis()) != MessageClaimResult.ACCEPTED) return false
        return runCatching { MetricsModule.recordExternalEvent(playerId, parsedSource, parsedEvent, wireOperationId) }.getOrDefault(false)
    }

    /** Called for accepted EcoJobs XP events; native work counters qualify, placeholders do not. */
    @JvmStatic
    fun recordJobWork(playerId: UUID, job: String): Boolean {
        if (job !in JobWorkObservation.JOBS) return false
        return runCatching { MetricsModule.recordJobWork(playerId, job) }.getOrDefault(false)
    }

    /** AFK entry, blocked work or consumer shutdown breaks the observation chain without adding time. */
    @JvmStatic
    fun breakJobWork(playerId: UUID) {
        runCatching { MetricsModule.breakJobWork(playerId) }
    }

    /** Test seam for the exact validated-to-sink boundary. */
    internal fun recordForSink(playerId: UUID, source: String, feature: String? = null, outcome: String? = null, action: String? = null, operationId: String, sink: (String, ProductFeature?, ProductOutcome?, ProductAction?) -> Boolean): Boolean =
        validateAndRecord(playerId, source, feature, outcome, action, operationId, sink)

    internal fun clearReplayState() {
        replayGuard = RecentMessageDeduplicator(DEDUPE_TTL_MILLIS, 4_096)
        eventReplayGuard = RecentMessageDeduplicator(DEDUPE_TTL_MILLIS, 4_096)
    }

    private fun validateAndRecord(playerId: UUID, source: String, feature: String?, outcome: String?, action: String?, operationId: String, sink: (String, ProductFeature?, ProductOutcome?, ProductAction?) -> Boolean): Boolean {
        val sourceKey = source.trim().lowercase()
        if (sourceKey !in acceptedSources || !operationPattern.matches(operationId)) return false
        val parsedFeature = feature?.let(::feature) ?: if (feature == null) null else return false
        val parsedOutcome = outcome?.let(::outcome) ?: if (outcome == null) null else return false
        val parsedAction = action?.let(::action) ?: if (action == null) null else return false
        if (parsedAction != null && (parsedFeature != null || parsedOutcome != null)) return false
        if (parsedFeature == null && parsedOutcome == null && parsedAction == null) return false
        val key = ProductPseudonym.of("$playerId|$sourceKey|$operationId")
        if (replayGuard.claim(key, System.currentTimeMillis()) != MessageClaimResult.ACCEPTED) return false
        return runCatching { sink(playerId.toString(), parsedFeature, parsedOutcome, parsedAction) }.getOrDefault(false)
    }

    private fun feature(raw: String): ProductFeature? = ProductFeature.entries.firstOrNull { it.label == raw.trim().lowercase() }
    private fun outcome(raw: String): ProductOutcome? = ProductOutcome.entries.firstOrNull { it.label == raw.trim().lowercase() }
    private fun action(raw: String): ProductAction? = ProductAction.entries.firstOrNull { it.label == raw.trim().lowercase() }
}
