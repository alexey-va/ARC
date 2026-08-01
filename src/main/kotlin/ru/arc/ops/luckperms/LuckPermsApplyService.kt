package ru.arc.ops.luckperms

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class LuckPermsApplyService(
    private val gateway: LuckPermsSubjectGateway,
    private val reviews: LuckPermsReviewStore,
    private val networkId: () -> String?,
) {
    private val applyInProgress = AtomicBoolean(false)

    fun preview(request: LpMutationRequest): CompletableFuture<LpReviewPlan> =
        gateway.get(request.subject).thenApply { live ->
            if (request.subject.type == LpSubjectType.USER && live == null) {
                throw NoSuchElementException("Unknown LuckPerms user UUID: ${request.subject.identifier}")
            }
            val snapshot = live ?: LpSubjectSnapshot(request.subject, emptyList())
            val duplicate =
                request.operations
                    .groupingBy { "${it.action}:${it.node.canonicalKey()}" }
                    .eachCount()
                    .entries
                    .firstOrNull { it.value > 1 }
            require(duplicate == null) { "Duplicate LuckPerms operation: ${duplicate?.key}" }

            val liveNodes = snapshot.nodes.toSet()
            request.operations
                .filter { it.action == LpOperationAction.UNSET }
                .forEach { operation ->
                    require(operation.node in liveNodes) {
                        "Exact LuckPerms unset target is not present: ${operation.node.canonicalKey()}"
                    }
                }
            val effective =
                request.operations
                    .filter { operation ->
                        when (operation.action) {
                            LpOperationAction.SET -> operation.node !in liveNodes
                            LpOperationAction.UNSET -> true
                        }
                    }.sortedWith(
                        compareBy<LpOperation>(
                            { if (it.action == LpOperationAction.SET) 0 else 1 },
                            { it.node.canonicalKey() },
                        ),
                    )
            val plan = LpPlan(request.subject, effective, request.reason.trim())
            val liveDigest = snapshotDigest(snapshot)
            val planDigest = planDigest(liveDigest, plan)
            reviews.create(
                liveDigest = liveDigest,
                planDigest = planDigest,
                plan = plan,
                warnings = if (effective.isEmpty()) listOf("No changes required") else emptyList(),
            )
        }

    fun apply(
        reviewToken: String,
        idempotencyKey: String,
        expectedSubject: LpSubjectRef? = null,
    ): CompletableFuture<LpApplyResult> {
        if (networkId() != "spawn") {
            return failedFuture(LpWriteGateException("LuckPerms writes are allowed only on spawn"))
        }
        val claim =
            try {
                reviews.claim(reviewToken, idempotencyKey, expectedSubject)
            } catch (t: Throwable) {
                return failedFuture(t)
            }
        claim.completed?.let { return CompletableFuture.completedFuture(it) }
        if (!applyInProgress.compareAndSet(false, true)) {
            reviews.invalidate(reviewToken, idempotencyKey)
            return failedFuture(LpConcurrentApplyException("Another LuckPerms apply is in progress"))
        }

        val record = claim.record
        val future =
            gateway.get(record.plan.subject).thenCompose { current ->
                val snapshot = current ?: LpSubjectSnapshot(record.plan.subject, emptyList())
                if (snapshotDigest(snapshot) != record.liveDigest) {
                    return@thenCompose failedFuture<LpApplyResult>(
                        LpStaleReviewException("LuckPerms subject changed after preview"),
                    )
                }
                if (record.plan.operations.isEmpty()) {
                    val result =
                        LpApplyResult(
                            subject = record.plan.subject,
                            status = LpApplyStatus.VERIFIED,
                            applied = emptyList(),
                            beforeDigest = record.liveDigest,
                            afterDigest = record.liveDigest,
                            message = "No changes required",
                        )
                    reviews.complete(reviewToken, idempotencyKey, result)
                    return@thenCompose CompletableFuture.completedFuture(result)
                }
                mutateAndVerify(record, snapshot, reviewToken, idempotencyKey)
            }
        return future.whenComplete { _, failure ->
            if (failure != null) reviews.invalidate(reviewToken, idempotencyKey)
            applyInProgress.set(false)
        }
    }

    private fun mutateAndVerify(
        record: LuckPermsReviewStore.ReviewRecord,
        before: LpSubjectSnapshot,
        reviewToken: String,
        idempotencyKey: String,
    ): CompletableFuture<LpApplyResult> {
        val additions = record.plan.operations.filter { it.action == LpOperationAction.SET }.map { it.node }.toSet()
        val removals = record.plan.operations.filter { it.action == LpOperationAction.UNSET }.map { it.node }.toSet()
        return gateway
            .mutate(record.plan.subject, additions, removals)
            .handle { after, failure ->
                when {
                    failure != null ->
                        rollback(record, before, additions, removals, reviewToken, idempotencyKey)
                    touchedStateMatches(after, additions, removals) -> {
                        val result =
                            LpApplyResult(
                                subject = record.plan.subject,
                                status = LpApplyStatus.VERIFIED,
                                applied = record.plan.operations,
                                beforeDigest = record.liveDigest,
                                afterDigest = snapshotDigest(after),
                            )
                        reviews.complete(reviewToken, idempotencyKey, result)
                        CompletableFuture.completedFuture(result)
                    }
                    else -> rollback(record, before, additions, removals, reviewToken, idempotencyKey)
                }
            }.thenCompose { it }
    }

    private fun rollback(
        record: LuckPermsReviewStore.ReviewRecord,
        before: LpSubjectSnapshot,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
        reviewToken: String,
        idempotencyKey: String,
    ): CompletableFuture<LpApplyResult> {
        val beforeNodes = before.nodes.toSet()
        val rollbackAdditions = removals.filterTo(linkedSetOf()) { it in beforeNodes }
        val rollbackRemovals = additions.filterTo(linkedSetOf()) { it !in beforeNodes }
        return gateway
            .mutate(record.plan.subject, rollbackAdditions, rollbackRemovals)
            .handle { rolledBack, failure ->
                val restored =
                    failure == null &&
                        rolledBack != null &&
                        rollbackAdditions.all { it in rolledBack.nodes } &&
                        rollbackRemovals.none { it in rolledBack.nodes }
                val result =
                    LpApplyResult(
                        subject = record.plan.subject,
                        status = if (restored) LpApplyStatus.ROLLED_BACK else LpApplyStatus.PARTIAL_FATAL,
                        applied = record.plan.operations,
                        beforeDigest = record.liveDigest,
                        afterDigest = rolledBack?.let(::snapshotDigest).orEmpty(),
                        message =
                            if (restored) {
                                "Verification failed; touched nodes were rolled back"
                            } else {
                                "Verification and rollback failed"
                            },
                    )
                reviews.complete(reviewToken, idempotencyKey, result)
                result
            }
    }

    private fun touchedStateMatches(
        snapshot: LpSubjectSnapshot,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): Boolean = additions.all { it in snapshot.nodes } && removals.none { it in snapshot.nodes }
}

internal fun snapshotDigest(snapshot: LpSubjectSnapshot): String =
    sha256(
        buildString {
            append(snapshot.subject.type.name).append(':').append(snapshot.subject.identifier).append('\n')
            snapshot.nodes.sortedBy(LpNodeSpec::canonicalKey).forEach { append(it.canonicalKey()).append('\n') }
        },
    )

internal fun planDigest(
    liveDigest: String,
    plan: LpPlan,
): String =
    sha256(
        buildString {
            append(liveDigest).append('\n')
            append(plan.subject.type.name).append(':').append(plan.subject.identifier).append('\n')
            append(plan.reason).append('\n')
            plan.operations.forEach { append(it.action.name).append(':').append(it.node.canonicalKey()).append('\n') }
        },
    )

private fun sha256(value: String): String =
    "sha256:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

private fun <T> failedFuture(error: Throwable): CompletableFuture<T> =
    CompletableFuture<T>().also { it.completeExceptionally(error) }
