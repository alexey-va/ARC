package ru.arc.audit

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Joins Jobs' per-action pre-payment events to its later buffered Vault payout.
 *
 * Jobs can combine many professions and action types into one provider deposit.
 * Components are therefore aggregated in memory, then scaled to the exact final
 * JobsPaymentEvent amount before the normal provider correlation tracker sees it.
 */
object JobsEconomyContextTracker {
    private data class ComponentKey(
        val job: String,
        val activity: String,
        val target: String?,
        val origin: String,
    )

    private data class MutableComponent(
        var amount: Double = 0.0,
        var occurrences: Int = 0,
    )

    private data class Bucket(
        val startedAt: Long,
        val components: LinkedHashMap<ComponentKey, MutableComponent> = linkedMapOf(),
    )

    private val pending = ConcurrentHashMap<UUID, Bucket>()

    fun capture(
        playerId: UUID,
        job: String?,
        activity: String?,
        target: String?,
        origin: String?,
        amount: Double,
        now: Long,
    ) {
        if (!amount.isFinite() || amount <= 0.0) return
        val requestedKey =
            ComponentKey(
                job = label(job, 32),
                activity = label(activity, 32),
                target = targetValue(target),
                origin = origin(origin),
            )
        pending.compute(playerId) { _, previous ->
            val bucket = previous?.takeIf { now - it.startedAt in 0..MAX_BUCKET_AGE_MILLIS } ?: Bucket(now)
            synchronized(bucket) {
                val key =
                    if (requestedKey in bucket.components || bucket.components.size < MAX_COMPONENTS - 1) {
                        requestedKey
                    } else {
                        OVERFLOW_KEY
                    }
                val component = bucket.components.getOrPut(key) { MutableComponent() }
                component.amount += amount
                component.occurrences = (component.occurrences + 1).coerceAtMost(1_000_000)
            }
            bucket
        }
    }

    fun finalizePayment(playerId: UUID, finalAmount: Double, now: Long): EconomyLedgerContext? {
        val bucket = pending.remove(playerId)
        if (!finalAmount.isFinite() || finalAmount <= 0.0) return null
        val raw =
            bucket?.takeIf { now - it.startedAt in 0..MAX_BUCKET_AGE_MILLIS }?.let {
                synchronized(it) {
                    it.components.entries.map { (key, value) -> key to value.copy() }
                }
            }.orEmpty()
        val rawTotal = raw.sumOf { (_, component) -> component.amount }
        val breakdown =
            if (!rawTotal.isFinite() || rawTotal <= 0.0) {
                emptyList()
            } else {
                var allocated = 0.0
                raw.mapIndexed { index, (key, component) ->
                    val scaled =
                        if (index == raw.lastIndex) {
                            finalAmount - allocated
                        } else {
                            finalAmount * component.amount / rawTotal
                        }
                    allocated += scaled
                    EconomyJobRewardComponent(
                        job = key.job,
                        activity = key.activity,
                        target = key.target,
                        origin = key.origin,
                        amount = scaled,
                        occurrences = component.occurrences,
                    )
                }.filter { component -> component.amount?.let { it.isFinite() && it > 0.0 } == true }
            }
        return EconomyLedgerContext(
            recordKind = EconomyRecordKind.ATTEMPT,
            status = EconomyEventStatus.SUBMITTED,
            requestedAmount = finalAmount,
            action = EconomyAction.JOB_REWARD.label,
            jobBreakdown = breakdown,
            capturedAt = now,
        )
    }

    fun discard(playerId: UUID) {
        pending.remove(playerId)
    }

    internal fun clear() = pending.clear()

    private fun label(value: String?, maxLength: Int): String =
        value.orEmpty().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(maxLength)
            .ifBlank { "unknown" }

    private fun targetValue(value: String?): String? =
        value.orEmpty().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_:./-]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { null }

    private fun origin(value: String?): String =
        value.orEmpty().lowercase(Locale.ROOT).takeIf { it in ALLOWED_ORIGINS } ?: "other"

    private val OVERFLOW_KEY = ComponentKey("other", "other", null, "other")
    private val ALLOWED_ORIGINS = setOf("not_applicable", "natural", "spawner", "player_generated", "custom", "other")
    private const val MAX_COMPONENTS = 64
    private const val MAX_BUCKET_AGE_MILLIS = 120_000L
}
