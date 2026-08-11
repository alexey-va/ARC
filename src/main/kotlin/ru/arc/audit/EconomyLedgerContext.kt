package ru.arc.audit

import com.google.gson.annotations.SerializedName
import kotlin.math.abs
import kotlin.math.max

/** Distinguishes actual balance changes from attempts that may have failed before mutation. */
enum class EconomyRecordKind {
    TRANSACTION,
    ATTEMPT,
}

/** Persisted outcome of a ledger record. */
enum class EconomyEventStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REVERTED,
    SUBMITTED,
}

/** Describes how the before/after balance pair was established. */
enum class BalanceEvidence {
    EXACT_BEFORE_AFTER,
    OBSERVED_AFTER_INFERRED_BEFORE,
    OBSERVED_UNCHANGED_FAILURE,
}

data class EconomyLedgerParty(
    @SerializedName("i")
    val id: String? = null,

    @SerializedName("n")
    val name: String? = null,

    @SerializedName("k")
    val kind: String? = null,
)

data class EconomyLedgerItem(
    @SerializedName("k")
    val key: String? = null,

    @SerializedName("m")
    val material: String? = null,

    @SerializedName("q")
    val quantity: Int? = null,

    @SerializedName("u")
    val unitPrice: Double? = null,

    @SerializedName("ci")
    val customItemId: String? = null,
)

/**
 * Optional v2 evidence attached to one persisted ledger record.
 *
 * Every field is nullable so Gson can continue to load the original compact
 * transaction representation without a migration or destructive rewrite.
 */
data class EconomyLedgerContext(
    @SerializedName("k")
    val recordKind: EconomyRecordKind? = null,

    @SerializedName("st")
    val status: EconomyEventStatus? = null,

    @SerializedName("aid")
    val accountId: String? = null,

    @SerializedName("pt")
    val providerTimestamp: Long? = null,

    @SerializedName("cid")
    val correlationId: String? = null,

    @SerializedName("cp")
    val counterparty: EconomyLedgerParty? = null,

    @SerializedName("w")
    val world: String? = null,

    @SerializedName("sid")
    val sessionId: String? = null,

    @SerializedName("ss")
    val sessionStartedAt: Long? = null,

    @SerializedName("bb")
    val balanceBefore: Double? = null,

    @SerializedName("ba")
    val balanceAfter: Double? = null,

    @SerializedName("be")
    val balanceEvidence: BalanceEvidence? = null,

    @SerializedName("ra")
    val requestedAmount: Double? = null,

    @SerializedName("ac")
    val action: String? = null,

    @SerializedName("sh")
    val shopId: String? = null,

    @SerializedName("it")
    val items: List<EconomyLedgerItem>? = null,

    @SerializedName("pc")
    val priceComponents: Map<String, Double>? = null,

    @SerializedName("fr")
    val failureReason: String? = null,

    @SerializedName("rw")
    val revertedWith: String? = null,

    @SerializedName("ca")
    val capturedAt: Long? = null,
) {
    val normalizedRecordKind: EconomyRecordKind get() = recordKind ?: EconomyRecordKind.TRANSACTION

    val normalizedStatus: EconomyEventStatus get() = status ?: EconomyEventStatus.SUCCEEDED

    val normalizedItems: List<EconomyLedgerItem> get() = items.orEmpty()

    val normalizedPriceComponents: Map<String, Double> get() = priceComponents.orEmpty()

    fun asTransaction(): EconomyLedgerContext =
        copy(
            recordKind = EconomyRecordKind.TRANSACTION,
            status = EconomyEventStatus.SUCCEEDED,
            failureReason = null,
        )
}

data class EconomyBalanceObservation(
    val before: Double,
    val after: Double,
    val evidence: BalanceEvidence,
) {
    companion object {
        fun inferredFromAfter(delta: Double, after: Double): EconomyBalanceObservation? {
            if (!delta.isFinite() || !after.isFinite()) return null
            val before = after - delta
            if (!before.isFinite()) return null
            return EconomyBalanceObservation(before, after, BalanceEvidence.OBSERVED_AFTER_INFERRED_BEFORE)
        }

        fun exact(before: Double, after: Double): EconomyBalanceObservation? {
            if (!before.isFinite() || !after.isFinite()) return null
            return EconomyBalanceObservation(before, after, BalanceEvidence.EXACT_BEFORE_AFTER)
        }

        fun unchanged(balance: Double): EconomyBalanceObservation? {
            if (!balance.isFinite()) return null
            return EconomyBalanceObservation(balance, balance, BalanceEvidence.OBSERVED_UNCHANGED_FAILURE)
        }
    }
}

internal fun approximatelyEqualMoney(expected: Double, actual: Double): Boolean =
    abs(expected - actual) <= max(0.01, max(abs(expected), abs(actual)) * 1e-8)
