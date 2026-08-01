package ru.arc.ops.luckperms

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
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
                contentHash = "sha256:test",
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
})

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
