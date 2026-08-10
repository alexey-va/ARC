package ru.arc.ops.luckperms

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class LuckPermsMigrationService(
    private val applyService: LuckPermsApplyService,
    private val store: LuckPermsMigrationStore,
    private val executor: Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arc-luckperms-migration").apply { isDaemon = true }
        },
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    init {
        store.loadAll().forEach { journal ->
            when (journal.state) {
                LpMigrationState.PREVIEWING -> {
                    journal.state = LpMigrationState.PREVIEW_FAILED
                    journal.currentSubjectIndex = null
                    journal.failures += "ARC restarted during migration preview; repeat preview"
                    store.save(journal)
                }
                LpMigrationState.READY -> {
                    if (!journal.hasDurableReview()) {
                        journal.state = LpMigrationState.PREVIEW_FAILED
                        journal.currentSubjectIndex = null
                        journal.failures += "Migration uses obsolete process-local review tokens; repeat preview"
                        store.save(journal)
                    }
                }
                LpMigrationState.APPLYING -> {
                    journal.recoveryPhase = LpMigrationRecoveryPhase.APPLY
                    journal.state = LpMigrationState.RECOVERY_REQUIRED
                    journal.failures += "ARC restarted during applying"
                    store.save(journal)
                }
                LpMigrationState.ROLLING_BACK -> {
                    if (journal.recoveryPhase == null) {
                        journal.recoveryPhase = LpMigrationRecoveryPhase.ROLLBACK
                    }
                    journal.state = LpMigrationState.RECOVERY_REQUIRED
                    journal.failures += "ARC restarted during rolling_back"
                    store.save(journal)
                }
                else -> Unit
            }
        }
    }

    @Synchronized
    fun previewMigration(request: LpMigrationRequest): CompletableFuture<LpMigrationStatus> {
        val normalized = gson.toJson(OpsLuckPermsJson.migrationMap(request))
        val contentHash = migrationHash(normalized)
        val existing = store.loadAll().firstOrNull { it.migrationId == request.id }
        if (existing != null) {
            require(existing.contentHash == contentHash) {
                "LuckPerms migration id '${request.id}' is immutable and already has different content"
            }
            if (existing.state != LpMigrationState.PREVIEW_FAILED) {
                return CompletableFuture.completedFuture(status(existing))
            }
            resetPreview(existing)
            executor.execute { preflightJournal(existing) }
            return CompletableFuture.completedFuture(status(existing))
        }
        require(
            store.loadAll().none {
                it.state in BLOCKING_MIGRATION_STATES
            },
        ) { "Another LuckPerms migration is active" }

        val journal =
            MigrationJournal(
                jobId = UUID.randomUUID().toString(),
                migrationId = request.id,
                contentHash = contentHash,
                state = LpMigrationState.PREVIEWING,
                requestJson = normalized,
            )
        store.save(journal)
        executor.execute { preflightJournal(journal) }
        return CompletableFuture.completedFuture(status(journal))
    }

    @Synchronized
    fun startMigration(
        jobId: String,
        idempotencyKey: String,
    ): LpMigrationStatus {
        require(idempotencyKey.isNotBlank()) { "LuckPerms migration idempotency key must not be blank" }
        val journal = requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" }
        if (journal.state == LpMigrationState.APPLYING) {
            require(journal.applyIdempotencyKey == idempotencyKey) {
                "Migration $jobId is already applying under a different idempotency key"
            }
            return status(journal)
        }
        if (journal.state == LpMigrationState.VERIFIED) {
            require(journal.applyIdempotencyKey == idempotencyKey) {
                "Migration $jobId was already applied under a different idempotency key"
            }
            return status(journal)
        }
        require(journal.state == LpMigrationState.READY) { "Migration $jobId is not ready: ${journal.state}" }
        require(journal.hasDurableReview()) { "Migration $jobId must be previewed again" }
        requireNoOtherActiveMigration(jobId)
        journal.applyIdempotencyKey = idempotencyKey
        journal.recoveryPhase = null
        journal.state = LpMigrationState.APPLYING
        store.save(journal)
        executor.execute { applyJournal(journal, idempotencyKey) }
        return status(journal)
    }

    fun status(jobId: String): LpMigrationStatus =
        status(requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" })

    @Synchronized
    fun rollbackMigration(
        jobId: String,
        idempotencyKey: String,
    ): LpMigrationStatus {
        require(idempotencyKey.isNotBlank()) { "LuckPerms migration rollback idempotency key must not be blank" }
        val journal = requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" }
        if (journal.state == LpMigrationState.ROLLING_BACK) {
            require(journal.rollbackIdempotencyKey == idempotencyKey) {
                "Migration $jobId is already rolling back under a different idempotency key"
            }
            return status(journal)
        }
        if (journal.state == LpMigrationState.ROLLED_BACK) {
            require(journal.rollbackIdempotencyKey == idempotencyKey) {
                "Migration $jobId was already rolled back under a different idempotency key"
            }
            return status(journal)
        }
        require(
            journal.state in
                setOf(
                    LpMigrationState.VERIFIED,
                    LpMigrationState.RECOVERY_REQUIRED,
                    LpMigrationState.PARTIAL_FATAL,
                ),
        ) { "Migration $jobId cannot be rolled back from ${journal.state}" }
        requireNoOtherActiveMigration(jobId)
        journal.rollbackIdempotencyKey = idempotencyKey
        journal.state = LpMigrationState.ROLLING_BACK
        store.save(journal)
        executor.execute { rollbackJournal(journal, idempotencyKey) }
        return status(journal)
    }

    private fun resetPreview(journal: MigrationJournal) {
        journal.state = LpMigrationState.PREVIEWING
        journal.planJson.clear()
        journal.liveDigests.clear()
        journal.planDigests.clear()
        journal.completedSubjects = 0
        journal.rollbackCompletedSubjects = 0
        journal.currentSubjectIndex = null
        journal.recoveryPhase = null
        journal.applyIdempotencyKey = null
        journal.rollbackIdempotencyKey = null
        journal.failures.clear()
        store.save(journal)
    }

    private fun preflightJournal(journal: MigrationJournal) {
        try {
            val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
            request.subjects.forEachIndexed { index, subject ->
                journal.currentSubjectIndex = index
                store.save(journal)
                val review = applyService.preview(subject).join()
                try {
                    journal.planJson += normalizedPlanJson(review.plan)
                    journal.liveDigests += review.liveDigest
                    journal.planDigests += review.planDigest
                    store.save(journal)
                } finally {
                    applyService.discardReview(review.reviewToken)
                }
            }
            journal.currentSubjectIndex = null
            journal.state = LpMigrationState.READY
            store.save(journal)
        } catch (t: Throwable) {
            journal.currentSubjectIndex = null
            journal.failures += rootMessage(t)
            journal.state = LpMigrationState.PREVIEW_FAILED
            store.save(journal)
        }
    }

    private fun applyJournal(
        journal: MigrationJournal,
        idempotencyKey: String,
    ) {
        try {
            val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
            for (index in journal.completedSubjects until request.subjects.size) {
                val review = refreshReview(journal, request.subjects[index], index)
                try {
                    journal.currentSubjectIndex = index
                    store.save(journal)
                } catch (t: Throwable) {
                    journal.currentSubjectIndex = null
                    applyService.discardReview(review.reviewToken)
                    throw t
                }
                val result =
                    try {
                        applyService.apply(review.reviewToken, "$idempotencyKey:$index").join()
                    } catch (t: Throwable) {
                        journal.currentSubjectIndex = null
                        store.save(journal)
                        throw t
                    }
                if (result.status == LpApplyStatus.ROLLED_BACK) {
                    journal.currentSubjectIndex = null
                    store.save(journal)
                }
                check(result.status == LpApplyStatus.VERIFIED) {
                    "Subject ${result.subject.identifier} ended in ${result.status}"
                }
                journal.completedSubjects = index + 1
                journal.currentSubjectIndex = null
                store.save(journal)
            }
            journal.recoveryPhase = null
            journal.state = LpMigrationState.VERIFIED
            store.save(journal)
        } catch (t: Throwable) {
            journal.failures += rootMessage(t)
            journal.recoveryPhase = LpMigrationRecoveryPhase.APPLY
            store.save(journal)
            rollbackJournal(journal, "$idempotencyKey:auto-rollback")
        }
    }

    private fun refreshReview(
        journal: MigrationJournal,
        request: LpMutationRequest,
        index: Int,
    ): LpReviewPlan {
        val review = applyService.preview(request).join()
        try {
            check(review.liveDigest == journal.liveDigests[index]) {
                "Subject ${request.subject.identifier} changed after migration preview"
            }
            check(review.planDigest == journal.planDigests[index]) {
                "Subject ${request.subject.identifier} plan changed after migration preview"
            }
            check(normalizedPlanJson(review.plan) == journal.planJson[index]) {
                "Subject ${request.subject.identifier} normalized plan changed after migration preview"
            }
            return review
        } catch (t: Throwable) {
            applyService.discardReview(review.reviewToken)
            throw t
        }
    }

    private fun rollbackJournal(
        journal: MigrationJournal,
        idempotencyKey: String,
    ) {
        journal.state = LpMigrationState.ROLLING_BACK
        store.save(journal)
        try {
            val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
            accountForInterruptedSubject(journal, request)
            journal.recoveryPhase = LpMigrationRecoveryPhase.ROLLBACK
            store.save(journal)
            val indexes =
                (0 until journal.completedSubjects)
                    .reversed()
                    .drop(journal.rollbackCompletedSubjects)
            indexes.forEach { index ->
                journal.currentSubjectIndex = index
                store.save(journal)
                val reviewedPlan = reviewedPlan(journal, request, index)
                when (classifyCurrentState(reviewedPlan)) {
                    LpTouchedState.BEFORE -> Unit
                    LpTouchedState.AFTER -> applyInverse(reviewedPlan, journal, idempotencyKey, index)
                    LpTouchedState.MIXED ->
                        error("Subject ${reviewedPlan.subject.identifier} has mixed touched state; refusing rollback")
                }
                journal.rollbackCompletedSubjects += 1
                journal.currentSubjectIndex = null
                journal.recoveryPhase = LpMigrationRecoveryPhase.ROLLBACK
                store.save(journal)
            }
            journal.currentSubjectIndex = null
            journal.recoveryPhase = null
            journal.state = LpMigrationState.ROLLED_BACK
            store.save(journal)
        } catch (t: Throwable) {
            journal.failures += rootMessage(t)
            journal.recoveryPhase = journal.recoveryPhase ?: LpMigrationRecoveryPhase.ROLLBACK
            journal.state = LpMigrationState.PARTIAL_FATAL
            store.save(journal)
        }
    }

    private fun accountForInterruptedSubject(
        journal: MigrationJournal,
        request: LpMigrationRequest,
    ) {
        val index = journal.currentSubjectIndex ?: return
        val plan = reviewedPlan(journal, request, index)
        when (journal.recoveryPhase) {
            LpMigrationRecoveryPhase.APPLY -> {
                require(index == journal.completedSubjects) {
                    "Interrupted apply journal has inconsistent subject counters"
                }
                when (classifyCurrentState(plan)) {
                    LpTouchedState.BEFORE -> journal.currentSubjectIndex = null
                    LpTouchedState.AFTER -> {
                        journal.completedSubjects = index + 1
                        journal.currentSubjectIndex = null
                    }
                    LpTouchedState.MIXED ->
                        error("Interrupted subject ${plan.subject.identifier} has mixed touched state")
                }
                store.save(journal)
            }
            LpMigrationRecoveryPhase.ROLLBACK -> {
                val expectedIndex = journal.completedSubjects - journal.rollbackCompletedSubjects - 1
                if (index == journal.completedSubjects) {
                    // Older journals could be mislabeled as ROLLBACK when classifying an
                    // interrupted APPLY failed. Recover the in-flight apply first.
                    journal.recoveryPhase = LpMigrationRecoveryPhase.APPLY
                    when (classifyCurrentState(plan)) {
                        LpTouchedState.BEFORE -> journal.currentSubjectIndex = null
                        LpTouchedState.AFTER -> {
                            journal.completedSubjects = index + 1
                            journal.currentSubjectIndex = null
                        }
                        LpTouchedState.MIXED ->
                            error("Interrupted subject ${plan.subject.identifier} has mixed touched state")
                    }
                    store.save(journal)
                    return
                }
                require(index == expectedIndex) { "Interrupted rollback journal has inconsistent subject counters" }
                when (classifyCurrentState(plan)) {
                    LpTouchedState.BEFORE -> {
                        journal.rollbackCompletedSubjects += 1
                        journal.currentSubjectIndex = null
                        store.save(journal)
                    }
                    LpTouchedState.AFTER -> Unit
                    LpTouchedState.MIXED ->
                        error("Interrupted rollback subject ${plan.subject.identifier} has mixed touched state")
                }
            }
            null -> Unit
        }
    }

    private fun applyInverse(
        reviewedPlan: LpMutationRequest,
        journal: MigrationJournal,
        idempotencyKey: String,
        index: Int,
    ) {
        val inverse =
            LpMutationRequest(
                subject = reviewedPlan.subject,
                operations =
                    reviewedPlan.operations
                        .asReversed()
                        .map {
                            it.copy(
                                action =
                                    if (it.action == LpOperationAction.SET) {
                                        LpOperationAction.UNSET
                                    } else {
                                        LpOperationAction.SET
                                    },
                            )
                        },
                reason = "Rollback migration ${journal.migrationId}",
            )
        val review = applyService.preview(inverse).join()
        val result = applyService.apply(review.reviewToken, "$idempotencyKey:$index").join()
        check(result.status == LpApplyStatus.VERIFIED) {
            "Rollback for ${inverse.subject.identifier} ended in ${result.status}"
        }
    }

    private fun reviewedPlan(
        journal: MigrationJournal,
        request: LpMigrationRequest,
        index: Int,
    ): LpMutationRequest =
        OpsLuckPermsJson.parseMutation(request.subjects[index].subject, journal.planJson[index])

    private fun classifyCurrentState(plan: LpMutationRequest): LpTouchedState {
        if (plan.operations.isEmpty()) return LpTouchedState.BEFORE
        val snapshot =
            applyService.snapshot(plan.subject).join()
                ?: if (plan.subject.type == LpSubjectType.GROUP) {
                    LpSubjectSnapshot(plan.subject, emptyList())
                } else {
                    error("LuckPerms user disappeared during migration recovery: ${plan.subject.identifier}")
                }
        val nodes = snapshot.nodes.toSet()
        val additions = plan.operations.filter { it.action == LpOperationAction.SET }.map { it.node }
        val removals = plan.operations.filter { it.action == LpOperationAction.UNSET }.map { it.node }
        val matchesBefore = additions.none { it in nodes } && removals.all { it in nodes }
        val matchesAfter = additions.all { it in nodes } && removals.none { it in nodes }
        return when {
            matchesBefore && !matchesAfter -> LpTouchedState.BEFORE
            matchesAfter && !matchesBefore -> LpTouchedState.AFTER
            else -> LpTouchedState.MIXED
        }
    }

    private fun normalizedPlanJson(plan: LpPlan): String =
        gson.toJson(
            mapOf(
                "version" to 1,
                "reason" to plan.reason,
                "operations" to plan.operations.map(OpsLuckPermsJson::operationMap),
            ),
        )

    private fun requireNoOtherActiveMigration(jobId: String) {
        require(
            store.loadAll().none {
                it.jobId != jobId &&
                    it.state in BLOCKING_MIGRATION_STATES
            },
        ) { "Another LuckPerms migration is active" }
    }

    private fun status(journal: MigrationJournal): LpMigrationStatus {
        val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
        val currentSubject =
            journal.currentSubjectIndex?.let { index ->
                request.subjects.getOrNull(index)?.subject
            }
        return LpMigrationStatus(
            jobId = journal.jobId,
            migrationId = journal.migrationId,
            contentHash = journal.contentHash,
            state = journal.state,
            totalSubjects = request.subjects.size,
            completedSubjects = journal.completedSubjects,
            rollbackCompletedSubjects = journal.rollbackCompletedSubjects,
            currentSubject = currentSubject,
            failures = journal.failures.toList(),
        )
    }

    private fun MigrationJournal.hasDurableReview(): Boolean {
        val subjectCount = OpsLuckPermsJson.parseMigration(requestJson).subjects.size
        return planJson.size == subjectCount &&
            liveDigests.size == subjectCount &&
            planDigests.size == subjectCount
    }

    private enum class LpTouchedState {
        BEFORE,
        AFTER,
        MIXED,
    }
}

internal fun migrationHash(normalizedJson: String): String =
    "sha256:" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(normalizedJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

private fun rootMessage(error: Throwable): String {
    var current = error
    while (current.cause != null) current = current.cause!!
    return current.message ?: current::class.java.simpleName
}

private val BLOCKING_MIGRATION_STATES =
    setOf(
        LpMigrationState.PREVIEWING,
        LpMigrationState.APPLYING,
        LpMigrationState.ROLLING_BACK,
        LpMigrationState.RECOVERY_REQUIRED,
        LpMigrationState.PARTIAL_FATAL,
    )
