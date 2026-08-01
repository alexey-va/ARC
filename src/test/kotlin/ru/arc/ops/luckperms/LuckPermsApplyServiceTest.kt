package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class LuckPermsApplyServiceTest : FreeSpec({
    val ref = LpSubjectRef(LpSubjectType.GROUP, "builder")
    val node = PermissionNodeSpec("example.build")

    "preview is read-only and binds a normalized plan" {
        val gateway = FakeGateway(ref, listOf(node))
        val service = service(gateway)

        val review =
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.UNSET, node)),
                    "remove test node",
                ),
            ).join()

        gateway.mutations shouldBe 0
        review.plan.operations shouldContainExactly listOf(LpOperation(LpOperationAction.UNSET, node))
        review.reviewToken.isBlank() shouldBe false
        review.planDigest.startsWith("sha256:") shouldBe true
    }

    "preview rejects an exact unset that is absent" {
        val service = service(FakeGateway(ref, emptyList()))

        val failure = shouldThrow<CompletionException> {
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.UNSET, node)),
                    "remove absent node",
                ),
            ).join()
        }
        (failure.cause is IllegalArgumentException) shouldBe true
    }

    "apply rejects stale state before mutation" {
        val gateway = FakeGateway(ref, emptyList())
        val service = service(gateway)
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()
        gateway.nodes += PermissionNodeSpec("concurrent.node")

        val failure = shouldThrow<CompletionException> {
            service.apply(review.reviewToken, "stale-1").join()
        }
        (failure.cause is LpStaleReviewException) shouldBe true
        gateway.mutations shouldBe 0
    }

    "apply is idempotent and verifies the exact touched state" {
        val gateway = FakeGateway(ref, emptyList())
        val service = service(gateway)
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val first = service.apply(review.reviewToken, "same-key").join()
        val second = service.apply(review.reviewToken, "same-key").join()

        first.status shouldBe LpApplyStatus.VERIFIED
        second shouldBe first
        gateway.mutations shouldBe 1
        gateway.nodes shouldContainExactly listOf(node)
    }

    "different idempotency key cannot reuse a consumed review" {
        val gateway = FakeGateway(ref, emptyList())
        val service = service(gateway)
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()
        service.apply(review.reviewToken, "first-key").join()

        val failure = shouldThrow<CompletionException> {
            service.apply(review.reviewToken, "other-key").join()
        }
        (failure.cause is LpReviewTokenException) shouldBe true
    }

    "non-spawn apply is rejected" {
        val gateway = FakeGateway(ref, emptyList())
        val service = service(gateway, networkId = "survival")
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val failure = shouldThrow<CompletionException> {
            service.apply(review.reviewToken, "wrong-server").join()
        }
        (failure.cause is LpWriteGateException) shouldBe true
        gateway.mutations shouldBe 0
    }

    "verification failure rolls back only touched nodes" {
        val unrelated = PermissionNodeSpec("unrelated.node")
        val gateway = FakeGateway(ref, listOf(unrelated), corruptFirstMutation = true)
        val service = service(gateway)
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val result = service.apply(review.reviewToken, "rollback-1").join()

        result.status shouldBe LpApplyStatus.ROLLED_BACK
        gateway.nodes shouldContainExactly listOf(unrelated)
        gateway.mutations shouldBe 2
    }

    "save failure also attempts inverse rollback" {
        val gateway = FakeGateway(ref, emptyList(), failFirstMutation = true)
        val service = service(gateway)
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val result = service.apply(review.reviewToken, "save-failure").join()

        result.status shouldBe LpApplyStatus.ROLLED_BACK
        gateway.nodes shouldBe emptyList()
        gateway.mutations shouldBe 2
    }

    "expired tokens fail closed" {
        val clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)
        val gateway = FakeGateway(ref, emptyList())
        val reviews = LuckPermsReviewStore(clock = clock, ttl = Duration.ZERO)
        val service = LuckPermsApplyService(gateway, reviews) { "spawn" }
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val failure = shouldThrow<CompletionException> {
            service.apply(review.reviewToken, "expired-1").join()
        }
        (failure.cause is LpReviewTokenException) shouldBe true
    }
})

private fun service(
    gateway: LuckPermsSubjectGateway,
    networkId: String = "spawn",
): LuckPermsApplyService =
    LuckPermsApplyService(
        gateway = gateway,
        reviews = LuckPermsReviewStore(),
        networkId = { networkId },
    )

private class FakeGateway(
    private val ref: LpSubjectRef,
    initialNodes: List<LpNodeSpec>,
    private val corruptFirstMutation: Boolean = false,
    private val failFirstMutation: Boolean = false,
) : LuckPermsSubjectGateway {
    val nodes = initialNodes.toMutableList()
    var mutations = 0

    override fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>> =
        CompletableFuture.completedFuture(listOf(LpSubjectSnapshot(ref, nodes.sortedBy { it.canonicalKey() })))

    override fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?> =
        CompletableFuture.completedFuture(
            if (ref == this.ref) LpSubjectSnapshot(ref, nodes.sortedBy { it.canonicalKey() }) else null,
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
        removals.forEach(nodes::remove)
        additions.filterNot(nodes::contains).forEach(nodes::add)
        if (failFirstMutation && mutations == 1) {
            return CompletableFuture.failedFuture(IllegalStateException("injected save failure"))
        }
        if (corruptFirstMutation && mutations == 1) {
            additions.forEach(nodes::remove)
        }
        return CompletableFuture.completedFuture(LpSubjectSnapshot(ref, nodes.sortedBy { it.canonicalKey() }))
    }
}
