package ru.arc.ops.luckperms

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LuckPermsReviewStore(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
    private val completedRetention: Duration = Duration.ofHours(24),
) {
    private val records = ConcurrentHashMap<String, ReviewRecord>()

    init {
        require(!ttl.isNegative) { "LuckPerms review TTL must not be negative" }
        require(!completedRetention.isNegative && !completedRetention.isZero) {
            "LuckPerms completed review retention must be positive"
        }
    }

    @Synchronized
    internal fun create(
        liveDigest: String,
        planDigest: String,
        plan: LpPlan,
        warnings: List<String>,
    ): LpReviewPlan {
        prune(clock.instant())
        val token = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(ttl)
        records[token] =
            ReviewRecord(
                token = token,
                liveDigest = liveDigest,
                planDigest = planDigest,
                plan = plan,
                warnings = warnings.toList(),
                expiresAt = expiresAt,
            )
        return LpReviewPlan(token, liveDigest, planDigest, plan, warnings.toList(), expiresAt)
    }

    @Synchronized
    internal fun claim(
        token: String,
        idempotencyKey: String,
        expectedSubject: LpSubjectRef? = null,
    ): ReviewClaim {
        require(idempotencyKey.isNotBlank()) { "LuckPerms idempotency key must not be blank" }
        val record = records[token] ?: throw LpReviewTokenException("Unknown LuckPerms review token")
        if (expectedSubject != null && record.plan.subject != expectedSubject) {
            throw LpReviewTokenException("LuckPerms review token belongs to a different subject")
        }
        record.completedResult?.let { completed ->
            if (record.idempotencyKey == idempotencyKey) return ReviewClaim(record, completed)
            throw LpReviewTokenException("LuckPerms review token was already consumed")
        }
        if (!clock.instant().isBefore(record.expiresAt)) {
            records.remove(token)
            throw LpReviewTokenException("Expired LuckPerms review token")
        }
        if (record.idempotencyKey != null) {
            if (record.idempotencyKey == idempotencyKey) {
                throw LpConcurrentApplyException("LuckPerms apply is still in progress")
            }
            throw LpReviewTokenException("LuckPerms review token was already claimed")
        }
        record.idempotencyKey = idempotencyKey
        return ReviewClaim(record, null)
    }

    @Synchronized
    internal fun invalidate(
        token: String,
        idempotencyKey: String,
    ) {
        val record = records[token] ?: return
        if (record.idempotencyKey == idempotencyKey && record.completedResult == null) {
            records.remove(token)
        }
    }

    @Synchronized
    internal fun release(
        token: String,
        idempotencyKey: String,
    ) {
        val record = records[token] ?: return
        if (record.idempotencyKey == idempotencyKey && record.completedResult == null) {
            record.idempotencyKey = null
        }
    }

    @Synchronized
    internal fun discard(token: String) {
        records.remove(token)
    }

    @Synchronized
    internal fun complete(
        token: String,
        idempotencyKey: String,
        result: LpApplyResult,
    ) {
        val record = records[token] ?: return
        check(record.idempotencyKey == idempotencyKey) { "LuckPerms review claim changed during apply" }
        record.completedResult = result
        record.completedAt = clock.instant()
    }

    private fun prune(now: Instant) {
        records.entries.removeIf { (_, record) ->
            val completedAt = record.completedAt
            if (completedAt == null) {
                !now.isBefore(record.expiresAt)
            } else {
                !now.isBefore(completedAt.plus(completedRetention))
            }
        }
    }

    internal data class ReviewClaim(
        val record: ReviewRecord,
        val completed: LpApplyResult?,
    )

    internal data class ReviewRecord(
        val token: String,
        val liveDigest: String,
        val planDigest: String,
        val plan: LpPlan,
        val warnings: List<String>,
        val expiresAt: Instant,
        var idempotencyKey: String? = null,
        var completedResult: LpApplyResult? = null,
        var completedAt: Instant? = null,
    )
}
