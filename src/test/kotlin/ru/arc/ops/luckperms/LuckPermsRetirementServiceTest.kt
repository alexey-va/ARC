package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class LuckPermsRetirementServiceTest : FreeSpec({
    "retirement boundary" - {
        "rejects malformed and protected requests" {
            shouldThrow<IllegalArgumentException> { OpsLuckPermsJson.parseRetirement("{\"version\":1,\"groups\":[\"default\"],\"reason\":\"x\"}") }
            shouldThrow<IllegalArgumentException> { OpsLuckPermsJson.parseRetirement("{\"version\":1,\"groups\":[],\"reason\":\"x\"}") }
        }

        "resume refuses a recreated previously deleted group" {
            val gateway = RetirementFakeGateway()
            val directory = Files.createTempDirectory("lp-retirement-resume-test")
            val store = LuckPermsRetirementStore(directory)
            store.save(RetirementJournal(
                reviewToken = "retry-token", idempotencyKey = "key",
                groups = listOf("legacy"), deleted = mutableListOf("legacy"),
                state = LpRetirementState.FAILED,
            ))
            val service = LuckPermsRetirementService(gateway, { "spawn" }, store)
            shouldThrow<CompletionException> { service.apply("retry-token", "key").join() }
            gateway.groups.containsKey("legacy") shouldBe true
            store.load("retry-token")!!.state shouldBe LpRetirementState.FAILED
        }

        "rejects referenced groups" {
            val gateway = RetirementFakeGateway()
            gateway.references = LpGroupReferenceReport(
                listOf("legacy"),
                listOf(LpGroupParentReference(LpSubjectRef(LpSubjectType.GROUP, "other"), InheritanceNodeSpec("legacy"))),
                emptyList(), emptyList(), emptyList(),
            )
            val service = service(gateway)
            val review = service.preview(LpRetirementRequest(listOf("legacy"), "retire legacy")).join()
            shouldThrow<CompletionException> { service.apply(review.reviewToken, "key").join() }
            gateway.groups.containsKey("legacy") shouldBe true
        }

        "rejects a stale group and succeeds with an idempotent retry" {
            val gateway = RetirementFakeGateway()
            val service = service(gateway)
            val review = service.preview(LpRetirementRequest(listOf("legacy"), "retire legacy")).join()
            gateway.groups["legacy"] = LpSubjectSnapshot(LpSubjectRef(LpSubjectType.GROUP, "legacy"), listOf(PermissionNodeSpec("changed")))
            shouldThrow<CompletionException> { service.apply(review.reviewToken, "key").join() }

            val fresh = service.preview(LpRetirementRequest(listOf("legacy"), "retire legacy")).join()
            val result = service.apply(fresh.reviewToken, "key-2").join()
            result.state shouldBe LpRetirementState.VERIFIED
            service.apply(fresh.reviewToken, "key-2").join().state shouldBe LpRetirementState.VERIFIED
            gateway.groups.containsKey("legacy") shouldBe false
        }
    }
})

private fun service(gateway: RetirementFakeGateway): LuckPermsRetirementService =
    LuckPermsRetirementService(gateway, { "spawn" }, LuckPermsRetirementStore(Files.createTempDirectory("lp-retirement-test")))

private class RetirementFakeGateway : LuckPermsSubjectGateway {
    val groups = mutableMapOf("legacy" to LpSubjectSnapshot(LpSubjectRef(LpSubjectType.GROUP, "legacy"), emptyList()))
    var references = LpGroupReferenceReport(listOf("legacy"), emptyList(), emptyList(), emptyList(), emptyList())

    override fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?> = CompletableFuture.completedFuture(groups[ref.identifier])
    override fun discoverGroupReferences(groups: Set<String>): CompletableFuture<LpGroupReferenceReport> = CompletableFuture.completedFuture(references)
    override fun deleteGroup(group: String): CompletableFuture<Boolean> = CompletableFuture.completedFuture(this.groups.remove(group) != null)
    override fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>> = CompletableFuture.completedFuture(emptyList())
    override fun lookupUser(name: String): CompletableFuture<LpUserIdentity?> = CompletableFuture.completedFuture(null)
    override fun check(request: LpPermissionCheckRequest): CompletableFuture<LpPermissionCheckResult?> = CompletableFuture.completedFuture(null)
    override fun mutate(ref: LpSubjectRef, additions: Set<LpNodeSpec>, removals: Set<LpNodeSpec>): CompletableFuture<LpSubjectSnapshot> = CompletableFuture.completedFuture(requireNotNull(get(ref).join()))
}
