package ru.arc.ops.luckperms

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LuckPermsMigrationServiceTest : FreeSpec({
    val group = LpSubjectRef(LpSubjectType.GROUP, "builder")
    val permission = PermissionNodeSpec("example.migration")

    "preflights every subject before the first mutation and journals verified apply" {
        val directory = Files.createTempDirectory("lp-migration-test")
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))
        val request = migration(group, permission)

        val preview = service.previewMigration(request).join()
        preview.state shouldBe LpMigrationState.READY
        gateway.mutations shouldBe 0
        Files.isRegularFile(directory.resolve("${preview.jobId}.json")) shouldBe true

        val applied = service.startMigration(preview.jobId, "migration-apply")
        applied.state shouldBe LpMigrationState.VERIFIED
        applied.completedSubjects shouldBe 1
        gateway.nodes.getValue(group) shouldBe mutableListOf(permission)
    }

    "rollback restores the exact before image" {
        val directory = Files.createTempDirectory("lp-migration-rollback")
        val original = PermissionNodeSpec("example.original")
        val gateway = MigrationFakeGateway(mapOf(group to listOf(original)))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))
        val request =
            LpMigrationRequest(
                1,
                "rollback-test",
                "bounded rollback test",
                listOf(
                    LpMutationRequest(
                        group,
                        listOf(
                            LpOperation(LpOperationAction.SET, permission),
                            LpOperation(LpOperationAction.UNSET, original),
                        ),
                        "bounded rollback test",
                    ),
                ),
            )
        val preview = service.previewMigration(request).join()
        service.startMigration(preview.jobId, "apply").state shouldBe LpMigrationState.VERIFIED

        val rolledBack = service.rollbackMigration(preview.jobId, "rollback")
        rolledBack.state shouldBe LpMigrationState.ROLLED_BACK
        gateway.nodes.getValue(group) shouldBe mutableListOf(original)
    }

    "migration id is immutable" {
        val directory = Files.createTempDirectory("lp-migration-immutable")
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))
        service.previewMigration(migration(group, permission)).join()

        shouldThrow<IllegalArgumentException> {
            service.previewMigration(migration(group, PermissionNodeSpec("different.node")))
        }
    }

    "restart classifies an interrupted apply as recovery required" {
        val directory = Files.createTempDirectory("lp-migration-recovery")
        val request = migration(group, permission)
        val json = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "interrupted-job",
                migrationId = request.id,
                contentHash = migrationHash(json),
                state = LpMigrationState.APPLYING,
                requestJson = json,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }

        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        service.status("interrupted-job").state shouldBe LpMigrationState.RECOVERY_REQUIRED
    }

    "duplicate migration subjects are rejected" {
        shouldThrow<IllegalArgumentException> {
            LpMigrationRequest(
                1,
                "duplicate-subjects",
                "bad migration",
                listOf(
                    LpMutationRequest(group, listOf(LpOperation(LpOperationAction.SET, permission)), "bad migration"),
                    LpMutationRequest(group, listOf(LpOperation(LpOperationAction.SET, permission)), "bad migration"),
                ),
            )
        }
    }

    "legacy journal without durable review fields loads with safe empty defaults" {
        val directory = Files.createTempDirectory("lp-migration-legacy-journal")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        Files.writeString(
            directory.resolve("legacy-job.json"),
            """
            {
              "version":1,
              "jobId":"legacy-job",
              "migrationId":"migration-test",
              "contentHash":"${migrationHash(requestJson)}",
              "state":"READY",
              "requestJson":${Gson().toJson(requestJson)},
              "reviewTokens":["dead-process-token"],
              "planJson":[],
              "completedSubjects":0,
              "rollbackCompletedSubjects":0,
              "failures":[]
            }
            """.trimIndent(),
        )
        val store = LuckPermsMigrationStore(directory)

        val loaded = store.load("legacy-job")!!

        loaded.liveDigests shouldBe emptyList()
        loaded.planDigests shouldBe emptyList()
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))
        service.status("legacy-job").state shouldBe LpMigrationState.PREVIEW_FAILED
    }

    "preview is scheduled asynchronously and returns PREVIEWING immediately" {
        val directory = Files.createTempDirectory("lp-migration-async-preview")
        val executor = QueuedExecutor()
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), executor)

        val preview = service.previewMigration(migration(group, permission)).join()

        preview.state shouldBe LpMigrationState.PREVIEWING
        executor.tasks.size shouldBe 1
        gateway.mutations shouldBe 0

        executor.runAll()
        service.status(preview.jobId).state shouldBe LpMigrationState.READY
    }

    "failed preview does not leave an active PREVIEWING journal" {
        val directory = Files.createTempDirectory("lp-migration-preview-failure")
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))
        val unknownUser =
            LpSubjectRef(
                LpSubjectType.USER,
                "00000000-0000-0000-0000-000000000777",
            )

        val failed = service.previewMigration(migration(unknownUser, permission)).join()
        service.status(failed.jobId).state shouldBe LpMigrationState.PREVIEW_FAILED

        val next = service.previewMigration(migration(group, permission).copy(id = "after-failure")).join()
        next.state shouldBe LpMigrationState.READY
    }

    "migration refreshes point reviews so token TTL cannot expire a large apply" {
        val directory = Files.createTempDirectory("lp-migration-token-refresh")
        val clock = MigrationMutableClock(Instant.parse("2026-08-02T00:00:00Z"))
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val reviews = LuckPermsReviewStore(clock = clock, ttl = Duration.ofSeconds(1))
        val apply = LuckPermsApplyService(gateway, reviews) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))

        val preview = service.previewMigration(migration(group, permission)).join()
        clock.advance(Duration.ofMinutes(1))
        val applied = service.startMigration(preview.jobId, "expired-preview-token")

        applied.state shouldBe LpMigrationState.VERIFIED
        gateway.nodes.getValue(group).shouldContainExactly(permission)
    }

    "pre-apply drift is never mistaken for this migration's in-flight mutation" {
        val directory = Files.createTempDirectory("lp-migration-pre-apply-drift")
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, LuckPermsMigrationStore(directory), Executor(Runnable::run))
        val preview = service.previewMigration(migration(group, permission)).join()
        gateway.nodes.getValue(group) += permission

        val result = service.startMigration(preview.jobId, "external-drift")

        result.state shouldBe LpMigrationState.ROLLED_BACK
        gateway.nodes.getValue(group).shouldContainExactly(permission)
        gateway.mutations shouldBe 0
    }

    "READY migration remains applicable after ARC restart" {
        val directory = Files.createTempDirectory("lp-migration-ready-restart")
        val store = LuckPermsMigrationStore(directory)
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val firstApply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val first = LuckPermsMigrationService(firstApply, store, Executor(Runnable::run))
        val preview = first.previewMigration(migration(group, permission)).join()
        preview.state shouldBe LpMigrationState.READY

        val restartedApply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val restarted = LuckPermsMigrationService(restartedApply, store, Executor(Runnable::run))
        val applied = restarted.startMigration(preview.jobId, "after-restart")

        applied.state shouldBe LpMigrationState.VERIFIED
        gateway.nodes.getValue(group).shouldContainExactly(permission)
    }

    "recovery rollback includes an in-flight subject saved before journal completion" {
        val directory = Files.createTempDirectory("lp-migration-inflight-apply")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val planJson =
            Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to request.reason,
                    "operations" to request.subjects.single().operations.map(OpsLuckPermsJson::operationMap),
                ),
            )
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "inflight-apply",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.APPLYING,
                requestJson = requestJson,
                planJson = mutableListOf(planJson),
                liveDigests = mutableListOf(snapshotDigest(LpSubjectSnapshot(group, emptyList()))),
                planDigests = mutableListOf("sha256:plan"),
                completedSubjects = 0,
                currentSubjectIndex = 0,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to listOf(permission)))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        service.status("inflight-apply").state shouldBe LpMigrationState.RECOVERY_REQUIRED
        val rolledBack = service.rollbackMigration("inflight-apply", "recover")

        rolledBack.state shouldBe LpMigrationState.ROLLED_BACK
        gateway.nodes.getValue(group) shouldBe emptyList()
    }

    "recovery resumes after rollback mutation completed but journal counter did not" {
        val directory = Files.createTempDirectory("lp-migration-inflight-rollback")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val planJson =
            Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to request.reason,
                    "operations" to request.subjects.single().operations.map(OpsLuckPermsJson::operationMap),
                ),
            )
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "inflight-rollback",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.ROLLING_BACK,
                requestJson = requestJson,
                planJson = mutableListOf(planJson),
                liveDigests = mutableListOf(snapshotDigest(LpSubjectSnapshot(group, emptyList()))),
                planDigests = mutableListOf("sha256:plan"),
                completedSubjects = 1,
                rollbackCompletedSubjects = 0,
                currentSubjectIndex = 0,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        val rolledBack = service.rollbackMigration("inflight-rollback", "resume")

        rolledBack.state shouldBe LpMigrationState.ROLLED_BACK
        gateway.nodes.getValue(group) shouldBe emptyList()
        gateway.mutations shouldBe 0
    }

    "restart preserves APPLY recovery phase when automatic rollback had only just started" {
        val directory = Files.createTempDirectory("lp-migration-auto-rollback-restart")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val planJson =
            Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to request.reason,
                    "operations" to request.subjects.single().operations.map(OpsLuckPermsJson::operationMap),
                ),
            )
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "auto-rollback-restart",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.ROLLING_BACK,
                requestJson = requestJson,
                planJson = mutableListOf(planJson),
                liveDigests = mutableListOf(snapshotDigest(LpSubjectSnapshot(group, emptyList()))),
                planDigests = mutableListOf("sha256:plan"),
                completedSubjects = 0,
                currentSubjectIndex = 0,
                recoveryPhase = LpMigrationRecoveryPhase.APPLY,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to listOf(permission)))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        service.status("auto-rollback-restart").state shouldBe LpMigrationState.RECOVERY_REQUIRED
        val rolledBack = service.rollbackMigration("auto-rollback-restart", "resume-auto")

        rolledBack.state shouldBe LpMigrationState.ROLLED_BACK
        gateway.nodes.getValue(group) shouldBe emptyList()
    }

    "rollback repairs a legacy journal whose in-flight apply was mislabeled as rollback" {
        val directory = Files.createTempDirectory("lp-migration-legacy-recovery-phase")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val planJson =
            Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to request.reason,
                    "operations" to request.subjects.single().operations.map(OpsLuckPermsJson::operationMap),
                ),
            )
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "legacy-rollback-phase",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.PARTIAL_FATAL,
                requestJson = requestJson,
                planJson = mutableListOf(planJson),
                liveDigests = mutableListOf(snapshotDigest(LpSubjectSnapshot(group, emptyList()))),
                planDigests = mutableListOf("sha256:plan"),
                completedSubjects = 0,
                rollbackCompletedSubjects = 0,
                currentSubjectIndex = 0,
                recoveryPhase = LpMigrationRecoveryPhase.ROLLBACK,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to listOf(permission)))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        val rolledBack = service.rollbackMigration("legacy-rollback-phase", "repair")

        rolledBack.state shouldBe LpMigrationState.ROLLED_BACK
        rolledBack.completedSubjects shouldBe 1
        rolledBack.rollbackCompletedSubjects shouldBe 1
        gateway.nodes.getValue(group) shouldBe emptyList()
    }

    "failed apply classification preserves its recovery phase for a later repair" {
        val directory = Files.createTempDirectory("lp-migration-preserve-recovery-phase")
        val secondPermission = PermissionNodeSpec("example.migration.second")
        val subjectRequest =
            LpMutationRequest(
                group,
                listOf(
                    LpOperation(LpOperationAction.SET, permission),
                    LpOperation(LpOperationAction.SET, secondPermission),
                ),
                "bounded migration test",
            )
        val request =
            LpMigrationRequest(
                version = 1,
                id = "migration-test",
                reason = "bounded migration test",
                subjects = listOf(subjectRequest),
            )
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val planJson =
            Gson().toJson(
                mapOf(
                    "version" to 1,
                    "reason" to request.reason,
                    "operations" to subjectRequest.operations.map(OpsLuckPermsJson::operationMap),
                ),
            )
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "preserve-apply-phase",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.ROLLING_BACK,
                requestJson = requestJson,
                planJson = mutableListOf(planJson),
                liveDigests = mutableListOf(snapshotDigest(LpSubjectSnapshot(group, emptyList()))),
                planDigests = mutableListOf("sha256:plan"),
                currentSubjectIndex = 0,
                recoveryPhase = LpMigrationRecoveryPhase.APPLY,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to listOf(permission)))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        service.rollbackMigration("preserve-apply-phase", "first-repair").state shouldBe LpMigrationState.PARTIAL_FATAL
        store.load("preserve-apply-phase")!!.recoveryPhase shouldBe LpMigrationRecoveryPhase.APPLY

        gateway.nodes.getValue(group) += secondPermission
        val repaired = service.rollbackMigration("preserve-apply-phase", "second-repair")

        repaired.state shouldBe LpMigrationState.ROLLED_BACK
        repaired.completedSubjects shouldBe 1
        repaired.rollbackCompletedSubjects shouldBe 1
        gateway.nodes.getValue(group) shouldBe emptyList()
    }

    "unresolved recovery blocks a different migration" {
        val directory = Files.createTempDirectory("lp-migration-block-recovery")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "recovery-blocker",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.RECOVERY_REQUIRED,
                requestJson = requestJson,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }
        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        shouldThrow<IllegalArgumentException> {
            service.previewMigration(
                migration(group, PermissionNodeSpec("different.node")).copy(id = "different-migration"),
            )
        }
    }

    "corrupt journal counters and request hash fail closed" {
        val directory = Files.createTempDirectory("lp-migration-corrupt-journal")
        val request = migration(group, permission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val store = LuckPermsMigrationStore(directory)

        shouldThrow<IllegalArgumentException> {
            store.save(
                MigrationJournal(
                    jobId = "bad-count",
                    migrationId = request.id,
                    contentHash = migrationHash(requestJson),
                    state = LpMigrationState.READY,
                    requestJson = requestJson,
                    completedSubjects = 2,
                ),
            )
            store.load("bad-count")
        }

        shouldThrow<IllegalArgumentException> {
            store.save(
                MigrationJournal(
                    jobId = "bad-hash",
                    migrationId = request.id,
                    contentHash = "sha256:tampered",
                    state = LpMigrationState.READY,
                    requestJson = requestJson,
                ),
            )
            store.load("bad-hash")
        }
    }

    "completed journal with an expired temporary node remains readable" {
        val directory = Files.createTempDirectory("lp-migration-expired-history")
        val expiredPermission =
            PermissionNodeSpec(
                "example.expired-history",
                expiresAt = Instant.parse("2020-01-01T00:00:00Z"),
            )
        val request = migration(group, expiredPermission)
        val requestJson = Gson().toJson(OpsLuckPermsJson.migrationMap(request))
        val store = LuckPermsMigrationStore(directory)
        store.save(
            MigrationJournal(
                jobId = "expired-history",
                migrationId = request.id,
                contentHash = migrationHash(requestJson),
                state = LpMigrationState.VERIFIED,
                requestJson = requestJson,
                planJson = mutableListOf("{}"),
                liveDigests = mutableListOf("sha256:live"),
                planDigests = mutableListOf("sha256:plan"),
                completedSubjects = 1,
            ),
        )
        val gateway = MigrationFakeGateway(mapOf(group to emptyList()))
        val apply = LuckPermsApplyService(gateway, LuckPermsReviewStore()) { "spawn" }

        val service = LuckPermsMigrationService(apply, store, Executor(Runnable::run))

        service.status("expired-history").state shouldBe LpMigrationState.VERIFIED
    }
})

private class QueuedExecutor : Executor {
    val tasks = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
        tasks += command
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

private class MigrationMutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

private fun migration(
    subject: LpSubjectRef,
    node: LpNodeSpec,
): LpMigrationRequest =
    LpMigrationRequest(
        version = 1,
        id = "migration-test",
        reason = "bounded migration test",
        subjects =
            listOf(
                LpMutationRequest(
                    subject,
                    listOf(LpOperation(LpOperationAction.SET, node)),
                    "bounded migration test",
                ),
            ),
    )

private class MigrationFakeGateway(initial: Map<LpSubjectRef, List<LpNodeSpec>>) : LuckPermsSubjectGateway {
    val nodes = initial.mapValues { (_, value) -> value.toMutableList() }.toMutableMap()
    var mutations = 0

    override fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>> =
        CompletableFuture.completedFuture(
            nodes.map { (ref, values) -> LpSubjectSnapshot(ref, values.sortedBy(LpNodeSpec::canonicalKey)) },
        )

    override fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?> =
        CompletableFuture.completedFuture(
            nodes[ref]?.let { LpSubjectSnapshot(ref, it.sortedBy(LpNodeSpec::canonicalKey)) }
                ?: if (ref.type == LpSubjectType.GROUP) LpSubjectSnapshot(ref, emptyList()) else null,
        )

    override fun lookupUser(name: String): CompletableFuture<LpUserIdentity?> =
        CompletableFuture.completedFuture(null)

    override fun check(request: LpPermissionCheckRequest): CompletableFuture<LpPermissionCheckResult?> =
        CompletableFuture.completedFuture(null)

    override fun mutate(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> {
        mutations += 1
        val target = nodes.getOrPut(ref) { mutableListOf() }
        removals.forEach(target::remove)
        additions.filterNot(target::contains).forEach(target::add)
        target.sortBy(LpNodeSpec::canonicalKey)
        return CompletableFuture.completedFuture(LpSubjectSnapshot(ref, target.toList()))
    }
}
