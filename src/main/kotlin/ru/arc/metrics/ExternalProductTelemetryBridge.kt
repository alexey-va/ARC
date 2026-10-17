package ru.arc.metrics

import java.util.UUID

/** ARC-owned validation and persistence boundary behind ArcTelemetryProvider. */
internal object ExternalProductTelemetryBridge {
    private const val MAX_OPERATION_ID = 80
    private val acceptedSources = setOf("arcecojobs", "arcfarms", "arcvotes", "arcranks", "arcbuilder", "arcduels", "arcevents", "arcgiveaways", "arcmounts", "arccontracts", "trails")
    private val operationPattern = Regex("[A-Za-z0-9_.:-]{1,$MAX_OPERATION_ID}")
    private val rawEventOperationPattern = Regex("[A-Za-z0-9_.:-]{1,256}")

    fun record(
        playerId: UUID,
        source: String,
        feature: String? = null,
        outcome: String? = null,
        action: String? = null,
        operationId: String,
    ): Boolean =
        validateAndRecord(playerId, source, feature, outcome, action, operationId) { id, f, o, a -> MetricsModule.recordExternalProduct(id, f, o, a, source.trim().lowercase(), operationId) }

    fun recordEvent(playerId: UUID, source: String, event: String, operationId: String): Boolean {
        val parsedSource = ExternalProductSource.entries.firstOrNull { it.label == source.trim().lowercase() } ?: return false
        val parsedEvent = ExternalProductEvent.entries.firstOrNull { it.label == event.trim().lowercase() } ?: return false
        if (parsedEvent.source != parsedSource || !rawEventOperationPattern.matches(operationId)) return false
        // The worker owns pseudonymization and replay state. True acknowledges FIFO admission.
        return runCatching { MetricsModule.recordExternalEvent(playerId, parsedSource, parsedEvent, operationId) }.getOrDefault(false)
    }

    /** Called for accepted EcoJobs XP events; native work counters qualify, placeholders do not. */
    fun recordJobWork(playerId: UUID, job: String): Boolean {
        if (job !in JobWorkObservation.JOBS) return false
        return runCatching { MetricsModule.recordJobWork(playerId, job) }.getOrDefault(false)
    }

    /** AFK entry, blocked work or consumer shutdown breaks the observation chain without adding time. */
    fun breakJobWork(playerId: UUID) {
        runCatching { MetricsModule.breakJobWork(playerId) }
    }

    /** Test seam for the exact validated-to-sink boundary. */
    internal fun recordForSink(playerId: UUID, source: String, feature: String? = null, outcome: String? = null, action: String? = null, operationId: String, sink: (String, ProductFeature?, ProductOutcome?, ProductAction?) -> Boolean): Boolean =
        validateAndRecord(playerId, source, feature, outcome, action, operationId, sink)

    private fun validateAndRecord(playerId: UUID, source: String, feature: String?, outcome: String?, action: String?, operationId: String, sink: (String, ProductFeature?, ProductOutcome?, ProductAction?) -> Boolean): Boolean {
        val sourceKey = source.trim().lowercase()
        if (sourceKey !in acceptedSources || !operationPattern.matches(operationId)) return false
        val parsedFeature = feature?.let(::feature) ?: if (feature == null) null else return false
        val parsedOutcome = outcome?.let(::outcome) ?: if (outcome == null) null else return false
        val parsedAction = action?.let(::action) ?: if (action == null) null else return false
        if (parsedAction != null && (parsedFeature != null || parsedOutcome != null)) return false
        if (parsedFeature == null && parsedOutcome == null && parsedAction == null) return false
        return runCatching { sink(playerId.toString(), parsedFeature, parsedOutcome, parsedAction) }.getOrDefault(false)
    }

    private fun feature(raw: String): ProductFeature? = ProductFeature.entries.firstOrNull { it.label == raw.trim().lowercase() }
    private fun outcome(raw: String): ProductOutcome? = ProductOutcome.entries.firstOrNull { it.label == raw.trim().lowercase() }
    private fun action(raw: String): ProductAction? = ProductAction.entries.firstOrNull { it.label == raw.trim().lowercase() }
}
