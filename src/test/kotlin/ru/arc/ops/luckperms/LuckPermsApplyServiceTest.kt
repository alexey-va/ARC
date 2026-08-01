package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock
import java.time.ZoneId
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

    "completed idempotent retry survives review expiry" {
        val clock = MutableClock(Instant.parse("2026-08-02T00:00:00Z"))
        val gateway = FakeGateway(ref, emptyList())
        val reviews = LuckPermsReviewStore(clock = clock, ttl = Duration.ofSeconds(1))
        val service = LuckPermsApplyService(gateway, reviews) { "spawn" }
        val review =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "add test node"),
            ).join()

        val first = service.apply(review.reviewToken, "retry-key").join()
        clock.advance(Duration.ofMinutes(1))
        val retried = service.apply(review.reviewToken, "retry-key").join()

        retried shouldBe first
        gateway.mutations shouldBe 1
    }

    "creating new reviews prunes abandoned expired tokens" {
        val clock = MutableClock(Instant.parse("2026-08-02T00:00:00Z"))
        val gateway = FakeGateway(ref, emptyList())
        val reviews = LuckPermsReviewStore(clock = clock, ttl = Duration.ofSeconds(1))
        val service = LuckPermsApplyService(gateway, reviews, clock) { "spawn" }
        val abandoned =
            service.preview(
                LpMutationRequest(ref, listOf(LpOperation(LpOperationAction.SET, node)), "abandoned"),
            ).join()
        clock.advance(Duration.ofMinutes(1))
        service.preview(
            LpMutationRequest(
                ref,
                listOf(LpOperation(LpOperationAction.SET, PermissionNodeSpec("example.second"))),
                "trigger cleanup",
            ),
        ).join()

        val failure = shouldThrow<CompletionException> {
            service.apply(abandoned.reviewToken, "old-token").join()
        }

        failure.cause?.message shouldBe "Unknown LuckPerms review token"
    }

    "set operation that expires after preview is rejected before mutation" {
        val clock = MutableClock(Instant.parse("2026-08-02T00:00:00Z"))
        val expiringNode = PermissionNodeSpec("example.temporary", expiresAt = clock.instant().plusSeconds(30))
        val gateway = FakeGateway(ref, emptyList())
        val reviews = LuckPermsReviewStore(clock = clock)
        val service = LuckPermsApplyService(gateway, reviews, clock) { "spawn" }
        val review =
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.SET, expiringNode)),
                    "temporary test",
                ),
            ).join()
        clock.advance(Duration.ofMinutes(1))

        val failure = shouldThrow<CompletionException> {
            service.apply(review.reviewToken, "expired-node").join()
        }

        (failure.cause is LpStaleReviewException) shouldBe true
        gateway.mutations shouldBe 0
    }

    "preview rejects an already expired set without mutation" {
        val clock = MutableClock(Instant.parse("2026-08-02T00:00:00Z"))
        val expiredNode =
            PermissionNodeSpec(
                "example.already-expired",
                expiresAt = clock.instant().minusSeconds(1),
            )
        val gateway = FakeGateway(ref, emptyList())
        val service = LuckPermsApplyService(gateway, LuckPermsReviewStore(clock = clock), clock) { "spawn" }

        val failure = shouldThrow<CompletionException> {
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.SET, expiredNode)),
                    "invalid temporary permission",
                ),
            ).join()
        }

        (failure.cause is IllegalArgumentException) shouldBe true
        gateway.mutations shouldBe 0
    }

    "global apply contention releases the rejected review token for a real retry" {
        val gateway = BlockingGateway(ref)
        val service = service(gateway)
        val first =
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.SET, PermissionNodeSpec("example.first"))),
                    "first",
                ),
            ).join()
        val second =
            service.preview(
                LpMutationRequest(
                    ref,
                    listOf(LpOperation(LpOperationAction.SET, PermissionNodeSpec("example.second"))),
                    "second",
                ),
            ).join()

        val firstApply = service.apply(first.reviewToken, "first")
        val contention = shouldThrow<CompletionException> {
            service.apply(second.reviewToken, "second").join()
        }
        (contention.cause is LpConcurrentApplyException) shouldBe true

        gateway.release()
        firstApply.join().status shouldBe LpApplyStatus.VERIFIED
        val retried = shouldThrow<CompletionException> {
            service.apply(second.reviewToken, "second").join()
        }
        (retried.cause is LpStaleReviewException) shouldBe true
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

private class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

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

private class BlockingGateway(
    private val ref: LpSubjectRef,
) : LuckPermsSubjectGateway {
    private val nodes = mutableListOf<LpNodeSpec>()
    private var blocked: CompletableFuture<LpSubjectSnapshot>? = null
    private var blockedAdditions: Set<LpNodeSpec> = emptySet()
    private var blockedRemovals: Set<LpNodeSpec> = emptySet()

    override fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>> =
        CompletableFuture.completedFuture(listOf(snapshot()))

    override fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?> =
        CompletableFuture.completedFuture(if (ref == this.ref) snapshot() else null)

    override fun lookupUser(name: String): CompletableFuture<LpUserIdentity?> =
        CompletableFuture.completedFuture(null)

    override fun check(request: LpPermissionCheckRequest): CompletableFuture<LpPermissionCheckResult?> =
        CompletableFuture.completedFuture(null)

    override fun mutate(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> {
        if (blocked == null) {
            blockedAdditions = additions
            blockedRemovals = removals
            return CompletableFuture<LpSubjectSnapshot>().also { blocked = it }
        }
        apply(additions, removals)
        return CompletableFuture.completedFuture(snapshot())
    }

    fun release() {
        apply(blockedAdditions, blockedRemovals)
        blocked!!.complete(snapshot())
    }

    private fun apply(
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ) {
        removals.forEach(nodes::remove)
        additions.filterNot(nodes::contains).forEach(nodes::add)
    }

    private fun snapshot(): LpSubjectSnapshot = LpSubjectSnapshot(ref, nodes.sortedBy(LpNodeSpec::canonicalKey))
}
