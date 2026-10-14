package ru.arc.ops

import com.google.gson.JsonObject
import ru.arc.integrations.contracts.requireContractsService
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyReconciliationRecord as SeasonMoneyJournalRecord
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyReconciliationResult as SeasonMoneyReconciliationApplyResult
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyReconciliationPreview as SeasonMoneyReconciliationPreview
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyReconciliationRequest as SeasonMoneyReconciliationRequest
import ru.arc.contracts.api.ArcContractsService.SeasonMoneyResolution as SeasonMoneyReconciliationResolution

object OpsSeasonMoneyReconciliationHandlers {
    fun list(limit: Int): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "leader" to requireContractsService().boardSnapshot().leader,
            "records" to requireContractsService().seasonMoneyReconciliations(limit).map(::recordView),
        )

    fun get(actionId: String): Map<String, Any?> {
        val record = requireContractsService().seasonMoneyReconciliation(actionId)
            ?: throw NoSuchElementException("Season money action not found")
        return linkedMapOf("ok" to true, "record" to recordView(record))
    }

    fun preview(body: JsonObject): Map<String, Any?> {
        val request = parseRequest(body, apply = false)
        return linkedMapOf(
            "ok" to true,
            "preview" to true,
            "reconciliation" to previewView(requireContractsService().previewSeasonMoneyReconciliation(request)),
        )
    }

    fun apply(body: JsonObject): Map<String, Any?> {
        val request = parseRequest(body, apply = true)
        val reviewDigest = requiredString(body, "reviewDigest")
        require(SHA256_PATTERN.matches(reviewDigest)) { "reviewDigest must be a lowercase SHA-256" }
        return applyView(requireContractsService().applySeasonMoneyReconciliation(request, reviewDigest))
    }

    private fun parseRequest(
        body: JsonObject,
        apply: Boolean,
    ): SeasonMoneyReconciliationRequest {
        requireOnly(body, if (apply) APPLY_FIELDS else PREVIEW_FIELDS)
        val resolution =
            SeasonMoneyReconciliationResolution.values().firstOrNull {
                it.name.lowercase() == requiredString(body, "resolution").lowercase()
            } ?: throw IllegalArgumentException("Unknown season money reconciliation resolution")
        return SeasonMoneyReconciliationRequest(
            requiredString(body, "actionId"),
            requiredLong(body, "expectedRevision"),
            resolution,
            requiredString(body, "operatorId"),
            requiredString(body, "operatorEvidence"),
            requiredString(body, "idempotencyKey"),
            requiredLong(body, "providerHistoryCheckedAt"),
            requiredLong(body, "providerBalanceAfterMinor"),
            optionalString(body, "providerTransactionId"),
            optionalString(body, "providerTransactionReason"),
        )
    }

    private fun recordView(record: SeasonMoneyJournalRecord): Map<String, Any?> =
        linkedMapOf(
            "actionId" to record.actionId,
            "seasonId" to record.seasonId,
            "kind" to record.kind,
            "targetId" to record.targetId,
            "playerId" to record.playerId,
            "amountMinor" to record.amountMinor,
            "withdrawalReason" to record.withdrawalReason,
            "status" to record.status,
            "revision" to record.revision,
            "updatedAt" to record.updatedAt,
            "reviewFromStatus" to record.reviewFromStatus,
            "reviewReason" to record.reviewReason,
            "reviewEvidence" to record.reviewEvidence,
            "balanceBeforeMinor" to record.balanceBeforeMinor,
            "balanceAfterMinor" to record.balanceAfterMinor,
            "providerTransactionId" to record.providerTransactionId,
            "reconciliation" to record.reconciliationResolution?.let {
                linkedMapOf(
                    "resolution" to record.reconciliationResolution,
                    "evidenceKind" to record.reconciliationEvidenceKind,
                    "operatorId" to record.operatorId,
                    "operatorEvidence" to record.operatorEvidence,
                    "idempotencyKey" to record.idempotencyKey,
                    "reviewDigest" to record.reviewDigest,
                    "reviewedRevision" to record.reviewedRevision,
                    "reviewFromStatus" to record.reconciliationReviewFromStatus,
                    "reviewReason" to record.reconciliationReviewReason,
                    "originalReviewEvidence" to record.originalReviewEvidence,
                    "providerHistoryCheckedAt" to record.providerHistoryCheckedAt,
                    "providerBalanceAfterMinor" to record.providerBalanceAfterMinor,
                    "providerTransactionId" to record.reconciliationProviderTransactionId,
                    "reconciledAt" to record.reconciledAt,
                )
            },
        )

    private fun previewView(preview: SeasonMoneyReconciliationPreview): Map<String, Any?> =
        linkedMapOf(
            "actionId" to preview.actionId,
            "reviewedRevision" to preview.reviewedRevision,
            "reviewFromStatus" to preview.reviewFromStatus,
            "reviewReason" to preview.reviewReason,
            "resolution" to preview.resolution,
            "evidenceKind" to preview.evidenceKind,
            "proposedStatus" to preview.proposedStatus,
            "reviewDigest" to preview.reviewDigest,
            "alreadyApplied" to preview.alreadyApplied,
            "commitsSeasonState" to preview.commitsSeasonState,
            "performsProviderMutation" to false,
        )

    private fun applyView(result: SeasonMoneyReconciliationApplyResult): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "applied" to !result.replayed,
            "replayed" to result.replayed,
            "reconciliation" to previewView(result.preview),
            "record" to recordView(result.record),
            "receipt" to result.receipt?.let {
                linkedMapOf(
                    "actionId" to it.actionId,
                    "kind" to it.kind,
                    "targetId" to it.targetId,
                    "playerId" to it.playerId,
                    "amountMinor" to it.amountMinor,
                    "committedAt" to it.committedAt,
                )
            },
        )

    private fun requiredString(body: JsonObject, field: String): String {
        val element = body.get(field) ?: throw IllegalArgumentException("Missing $field")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) { "$field must be a string" }
        return element.asString
    }

    private fun optionalString(body: JsonObject, field: String): String? {
        val element = body.get(field) ?: return null
        require(!element.isJsonNull && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$field must be a string"
        }
        return element.asString
    }

    private fun requiredLong(body: JsonObject, field: String): Long {
        val element = body.get(field) ?: throw IllegalArgumentException("Missing $field")
        require(!element.isJsonNull && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$field must be an integer"
        }
        return runCatching { element.asBigDecimal.longValueExact() }
            .getOrElse { throw IllegalArgumentException("$field must be an exact 64-bit integer") }
    }

    private fun requireOnly(body: JsonObject, allowed: Set<String>) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) {
            "season money reconciliation contains unknown fields: ${unknown.sorted().joinToString()}"
        }
    }

    private val PREVIEW_FIELDS =
        setOf(
            "actionId",
            "expectedRevision",
            "resolution",
            "operatorId",
            "operatorEvidence",
            "idempotencyKey",
            "providerHistoryCheckedAt",
            "providerBalanceAfterMinor",
            "providerTransactionId",
            "providerTransactionReason",
        )
    private val APPLY_FIELDS = PREVIEW_FIELDS + "reviewDigest"
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
