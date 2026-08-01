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
        store.loadAll().filter { it.state == LpMigrationState.APPLYING || it.state == LpMigrationState.ROLLING_BACK }.forEach {
            val interruptedState = it.state
            it.state = LpMigrationState.RECOVERY_REQUIRED
            it.failures += "ARC restarted during ${interruptedState.name.lowercase()}"
            store.save(it)
        }
    }

    fun previewMigration(request: LpMigrationRequest): CompletableFuture<LpMigrationStatus> {
        val normalized = gson.toJson(OpsLuckPermsJson.migrationMap(request))
        val contentHash = migrationHash(normalized)
        store.loadAll().firstOrNull { it.migrationId == request.id }?.let { existing ->
            require(existing.contentHash == contentHash) {
                "LuckPerms migration id '${request.id}' is immutable and already has different content"
            }
            return CompletableFuture.completedFuture(status(existing))
        }
        require(
            store.loadAll().none {
                it.state in
                    setOf(
                        LpMigrationState.PREVIEWING,
                        LpMigrationState.APPLYING,
                        LpMigrationState.ROLLING_BACK,
                    )
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
        var chain = CompletableFuture.completedFuture(Unit)
        request.subjects.forEach { subject ->
            chain =
                chain.thenCompose {
                    applyService.preview(subject).thenApply { review ->
                        journal.reviewTokens += review.reviewToken
                        journal.planJson +=
                            gson.toJson(
                                mapOf(
                                    "version" to 1,
                                    "reason" to review.plan.reason,
                                    "operations" to review.plan.operations.map(OpsLuckPermsJson::operationMap),
                                ),
                            )
                        store.save(journal)
                    }
                }
        }
        return chain.thenApply {
            journal.state = LpMigrationState.READY
            store.save(journal)
            status(journal)
        }
    }

    fun startMigration(
        jobId: String,
        idempotencyKey: String,
    ): LpMigrationStatus {
        require(idempotencyKey.isNotBlank()) { "LuckPerms migration idempotency key must not be blank" }
        val journal = requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" }
        if (journal.state == LpMigrationState.VERIFIED) return status(journal)
        require(journal.state == LpMigrationState.READY) { "Migration $jobId is not ready: ${journal.state}" }
        journal.state = LpMigrationState.APPLYING
        store.save(journal)
        executor.execute { applyJournal(journal, idempotencyKey) }
        return status(journal)
    }

    fun status(jobId: String): LpMigrationStatus =
        status(requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" })

    fun rollbackMigration(
        jobId: String,
        idempotencyKey: String,
    ): LpMigrationStatus {
        require(idempotencyKey.isNotBlank()) { "LuckPerms migration rollback idempotency key must not be blank" }
        val journal = requireNotNull(store.load(jobId)) { "Unknown LuckPerms migration job: $jobId" }
        require(
            journal.state in
                setOf(
                    LpMigrationState.VERIFIED,
                    LpMigrationState.RECOVERY_REQUIRED,
                    LpMigrationState.PARTIAL_FATAL,
                ),
        ) { "Migration $jobId cannot be rolled back from ${journal.state}" }
        journal.state = LpMigrationState.ROLLING_BACK
        store.save(journal)
        executor.execute { rollbackJournal(journal, idempotencyKey) }
        return status(journal)
    }

    private fun applyJournal(
        journal: MigrationJournal,
        idempotencyKey: String,
    ) {
        try {
            for (index in journal.completedSubjects until journal.reviewTokens.size) {
                journal.currentSubjectIndex = index
                store.save(journal)
                val result = applyService.apply(journal.reviewTokens[index], "$idempotencyKey:$index").join()
                check(result.status == LpApplyStatus.VERIFIED) {
                    "Subject ${result.subject.identifier} ended in ${result.status}"
                }
                journal.completedSubjects = index + 1
                store.save(journal)
            }
            journal.currentSubjectIndex = null
            journal.state = LpMigrationState.VERIFIED
            store.save(journal)
        } catch (t: Throwable) {
            journal.failures += rootMessage(t)
            store.save(journal)
            rollbackJournal(journal, "$idempotencyKey:auto-rollback")
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
            val alreadyRolledBack = journal.rollbackCompletedSubjects
            val indexes = (0 until journal.completedSubjects).reversed().drop(alreadyRolledBack)
            indexes.forEach { index ->
                journal.currentSubjectIndex = index
                store.save(journal)
                val reviewedPlan = OpsLuckPermsJson.parseMutation(request.subjects[index].subject, journal.planJson[index])
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
                journal.rollbackCompletedSubjects += 1
                store.save(journal)
            }
            journal.currentSubjectIndex = null
            journal.state = LpMigrationState.ROLLED_BACK
            store.save(journal)
        } catch (t: Throwable) {
            journal.failures += rootMessage(t)
            journal.state = LpMigrationState.PARTIAL_FATAL
            store.save(journal)
        }
    }

    private fun status(journal: MigrationJournal): LpMigrationStatus {
        val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
        return LpMigrationStatus(
            jobId = journal.jobId,
            migrationId = journal.migrationId,
            contentHash = journal.contentHash,
            state = journal.state,
            totalSubjects = request.subjects.size,
            completedSubjects = journal.completedSubjects,
            rollbackCompletedSubjects = journal.rollbackCompletedSubjects,
            currentSubject = journal.currentSubjectIndex?.let { request.subjects[it].subject },
            failures = journal.failures.toList(),
        )
    }
}

private fun migrationHash(normalizedJson: String): String =
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
