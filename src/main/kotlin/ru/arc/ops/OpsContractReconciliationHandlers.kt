package ru.arc.ops

import com.google.gson.JsonObject
import ru.arc.contracts.api.ArcContractsService.SubmissionReconciliationRecord as ContractSubmissionJournalRecord
import ru.arc.contracts.api.ArcContractsService.SubmissionReconciliationResult as ContractSubmissionReconciliationApplyResult
import ru.arc.contracts.api.ArcContractsService.SubmissionReconciliationPreview as ContractSubmissionReconciliationPreview
import ru.arc.contracts.api.ArcContractsService.SubmissionReconciliationRequest as ContractSubmissionReconciliationRequest
import ru.arc.contracts.api.ArcContractsService.SubmissionResolution as ContractSubmissionReconciliationResolution
import ru.arc.integrations.contracts.requireContractsService

object OpsContractReconciliationHandlers {
    fun list(limit: Int): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "leader" to requireContractsService().boardSnapshot().leader,
            "records" to requireContractsService().submissionReconciliations(limit).map(::recordView),
        )

    fun get(submissionId: String): Map<String, Any?> {
        val record = requireContractsService().submissionReconciliation(submissionId)
            ?: throw NoSuchElementException("Contract submission not found")
        return linkedMapOf("ok" to true, "record" to recordView(record))
    }

    fun preview(body: JsonObject): Map<String, Any?> {
        val request = parseRequest(body, apply = false)
        return linkedMapOf(
            "ok" to true,
            "preview" to true,
            "reconciliation" to previewView(requireContractsService().previewSubmissionReconciliation(request)),
        )
    }

    fun apply(body: JsonObject): Map<String, Any?> {
        val request = parseRequest(body, apply = true)
        val reviewDigest = requiredString(body, "reviewDigest")
        require(SHA256_PATTERN.matches(reviewDigest)) { "reviewDigest must be a lowercase SHA-256" }
        val result = requireContractsService().applySubmissionReconciliation(request, reviewDigest)
        return applyView(result)
    }

    private fun parseRequest(
        body: JsonObject,
        apply: Boolean,
    ): ContractSubmissionReconciliationRequest {
        requireOnly(
            body,
            if (apply) APPLY_FIELDS else PREVIEW_FIELDS,
            "contract reconciliation",
        )
        val resolution =
            ContractSubmissionReconciliationResolution.values().firstOrNull {
                it.name.lowercase() == requiredString(body, "resolution").lowercase()
            } ?: throw IllegalArgumentException("Unknown contract reconciliation resolution")
        return ContractSubmissionReconciliationRequest(
            requiredString(body, "submissionId"),
            requiredLong(body, "expectedRevision"),
            resolution,
            requiredString(body, "operatorId"),
            requiredString(body, "operatorEvidence"),
            requiredString(body, "idempotencyKey"),
            optionalLong(body, "providerBalanceAfterMinor"),
            optionalString(body, "providerTransactionId"),
            optionalString(body, "providerTransactionReason"),
        )
    }

    private fun recordView(record: ContractSubmissionJournalRecord): Map<String, Any?> =
        linkedMapOf(
            "submissionId" to record.submissionId,
            "contractId" to record.contractId,
            "contractWindowStartsAt" to record.contractWindowStartsAt,
            "itemKey" to record.itemKey,
            "playerId" to record.playerId,
            "acceptedQuantity" to record.acceptedQuantity,
            "payoutMinor" to record.payoutMinor,
            "payoutReason" to record.payoutReason,
            "status" to record.status,
            "revision" to record.revision,
            "updatedAt" to record.updatedAt,
            "reviewFromStatus" to record.reviewFromStatus,
            "reviewReason" to record.reviewReason,
            "reviewEvidence" to record.reviewEvidence,
            "providerBalanceBeforeMinor" to record.providerBalanceBeforeMinor,
            "providerBalanceAfterMinor" to record.providerBalanceAfterMinor,
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
                    "reconciledAt" to record.reconciledAt,
                )
            },
        )

    private fun previewView(preview: ContractSubmissionReconciliationPreview): Map<String, Any?> =
        linkedMapOf(
            "submissionId" to preview.submissionId,
            "reviewedRevision" to preview.reviewedRevision,
            "reviewFromStatus" to preview.reviewFromStatus,
            "reviewReason" to preview.reviewReason,
            "resolution" to preview.resolution,
            "evidenceKind" to preview.evidenceKind,
            "proposedStatus" to preview.proposedStatus,
            "reviewDigest" to preview.reviewDigest,
            "alreadyApplied" to preview.alreadyApplied,
            "commitsContractState" to preview.commitsContractState,
            "performsInventoryMutation" to false,
            "performsProviderMutation" to false,
        )

    private fun applyView(result: ContractSubmissionReconciliationApplyResult): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "applied" to !result.replayed,
            "replayed" to result.replayed,
            "reconciliation" to previewView(result.preview),
            "record" to recordView(result.record),
            "receipt" to result.receipt?.let {
                linkedMapOf(
                    "submissionId" to it.submissionId,
                    "playerId" to it.playerId,
                    "quantity" to it.quantity,
                    "payoutMinor" to it.payoutMinor,
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

    private fun requiredLong(body: JsonObject, field: String): Long =
        optionalLong(body, field) ?: throw IllegalArgumentException("Missing $field")

    private fun optionalLong(body: JsonObject, field: String): Long? {
        val element = body.get(field) ?: return null
        require(!element.isJsonNull && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "$field must be an integer"
        }
        return runCatching { element.asBigDecimal.longValueExact() }
            .getOrElse { throw IllegalArgumentException("$field must be an exact 64-bit integer") }
    }

    private fun requireOnly(body: JsonObject, allowed: Set<String>, label: String) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "$label contains unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private val PREVIEW_FIELDS =
        setOf(
            "submissionId",
            "expectedRevision",
            "resolution",
            "operatorId",
            "operatorEvidence",
            "idempotencyKey",
            "providerBalanceAfterMinor",
            "providerTransactionId",
            "providerTransactionReason",
        )
    private val APPLY_FIELDS = PREVIEW_FIELDS + "reviewDigest"
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
