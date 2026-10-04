package ru.arc.metrics

import ru.arc.audit.ExternalEconomyAuditBridge
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

internal object ArcTelemetryProviderBridge : ArcTelemetryProvider {
    override fun recordActivity(playerId: UUID, source: String, event: String, subject: String?, operationId: String?, attributes: Map<String, String>): Boolean =
        ru.arc.metrics.telemetry.PlayerTelemetryModule.record(playerId, source, event, subject, operationId, attributes)

    override fun record(
        playerId: UUID,
        source: String,
        feature: String?,
        outcome: String?,
        action: String?,
        operationId: String,
    ): Boolean = ExternalProductTelemetryBridge.record(playerId, source, feature, outcome, action, operationId).also { accepted ->
        if (accepted) ru.arc.metrics.telemetry.PlayerTelemetryModule.record(playerId, source, "mechanic.observation",
            outcome ?: feature ?: action, operationId, buildMap {
                feature?.let { put("feature", it) }; outcome?.let { put("outcome", it) }; action?.let { put("action", it) }
            })
    }

    override fun recordEvent(playerId: UUID, source: String, event: String, operationId: String): Boolean =
        ExternalProductTelemetryBridge.recordEvent(playerId, source, event, operationId).also { accepted ->
            if (accepted) ru.arc.metrics.telemetry.PlayerTelemetryModule.record(playerId, source, event, null, operationId)
        }

    override fun recordJobWork(playerId: UUID, job: String): Boolean =
        ExternalProductTelemetryBridge.recordJobWork(playerId, job)

    override fun breakJobWork(playerId: UUID) = ExternalProductTelemetryBridge.breakJobWork(playerId)

    override fun markJobReward(playerId: UUID, job: String, amount: Double): String? =
        ExternalEconomyAuditBridge.markJobReward(playerId, job, amount)

    override fun markExternalReward(
        playerId: UUID,
        source: String,
        action: String,
        amount: Double,
        currency: String?,
        rewardId: String?,
    ): String? = ExternalEconomyAuditBridge.markExternalReward(playerId, source, action, amount, currency, rewardId)

    override fun cancelAudit(playerId: UUID, token: String?) =
        ExternalEconomyAuditBridge.cancel(playerId, token)

    override fun observeUi(payload: Map<String, Any>) = MetricsModule.observeExternalUi(payload)
}
