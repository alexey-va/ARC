package ru.arc.ops.luckperms

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LuckPermsRetirementStore(
    private val directory: Path,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    init { Files.createDirectories(directory) }

    @Synchronized fun save(journal: RetirementJournal) {
        require(SAFE_TOKEN.matches(journal.reviewToken)) { "Unsafe LuckPerms retirement journal id" }
        val temp = Files.createTempFile(directory, ".${journal.reviewToken}-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(journal), StandardCharsets.UTF_8)
            try {
                Files.move(temp, directory.resolve("${journal.reviewToken}.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, directory.resolve("${journal.reviewToken}.json"), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temp) }
    }

    @Synchronized fun load(token: String): RetirementJournal? {
        require(SAFE_TOKEN.matches(token)) { "Unsafe LuckPerms retirement review token" }
        val path = directory.resolve("$token.json")
        return if (Files.isRegularFile(path)) gson.fromJson(Files.readString(path), RetirementJournal::class.java) else null
    }

    companion object { private val SAFE_TOKEN = Regex("[a-zA-Z0-9-]+") }
}

data class RetirementJournal(
    val version: Int = 1,
    val reviewToken: String = "",
    val idempotencyKey: String = "",
    val groups: List<String> = emptyList(),
    val beforeDigests: Map<String, String> = emptyMap(),
    val beforeImages: Map<String, String> = emptyMap(),
    var deleted: MutableList<String> = mutableListOf(),
    var state: LpRetirementState = LpRetirementState.BEFORE_DELETE,
    var failure: String? = null,
    val createdAt: String = Instant.now().toString(),
)

class LuckPermsRetirementService(
    private val gateway: LuckPermsSubjectGateway,
    private val networkId: () -> String?,
    private val store: LuckPermsRetirementStore,
) {
    private val reviews = ConcurrentHashMap<String, LpRetirementReview>()
    private val inProgress = AtomicBoolean(false)

    fun references(groups: List<String>): CompletableFuture<LpGroupReferenceReport> =
        gateway.discoverGroupReferences(groups.toSet())

    fun preview(request: LpRetirementRequest): CompletableFuture<LpRetirementReview> =
        CompletableFuture.allOf(*request.groups.map { gateway.get(LpSubjectRef(LpSubjectType.GROUP, it)) }.toTypedArray())
            .thenCompose {
                val snapshots = request.groups.associateWith { gateway.get(LpSubjectRef(LpSubjectType.GROUP, it)).join() }
                require(snapshots.values.none { it == null }) { "Unknown LuckPerms group" }
                gateway.discoverGroupReferences(request.groups.toSet()).thenApply { refs ->
                    val review = LpRetirementReview(
                        UUID.randomUUID().toString(),
                        request,
                        snapshots.mapValues { snapshotDigest(requireNotNull(it.value)) },
                        refs,
                    )
                    reviews[review.reviewToken] = review
                    review
                }
            }

    fun apply(reviewToken: String, idempotencyKey: String): CompletableFuture<LpRetirementResult> {
        require(idempotencyKey.isNotBlank()) { "LuckPerms retirement idempotency key must not be blank" }
        if (networkId() != "spawn") return failedFuture(LpWriteGateException("LuckPerms writes are allowed only on spawn"))
        store.load(reviewToken)?.let { journal ->
            if (journal.idempotencyKey != idempotencyKey) return failedFuture(LpReviewTokenException("LuckPerms retirement journal was already claimed"))
            if (journal.state == LpRetirementState.VERIFIED) {
                return CompletableFuture.completedFuture(LpRetirementResult(journal.groups, journal.state, journal.beforeDigests, journal.deleted, "Already verified"))
            }
            if (inProgress.compareAndSet(false, true)) {
                return verifyAndResume(journal).whenComplete { _, _ -> inProgress.set(false) }
            }
            return failedFuture(LpConcurrentApplyException("Another LuckPerms retirement is in progress"))
        }
        val review = reviews[reviewToken] ?: return failedFuture(LpReviewTokenException("Unknown LuckPerms retirement review token"))
        if (!inProgress.compareAndSet(false, true)) return failedFuture(LpConcurrentApplyException("Another LuckPerms retirement is in progress"))
        val future = verifyAndDelete(review, idempotencyKey)
        return future.whenComplete { _, _ -> inProgress.set(false) }
    }

    private fun verifyAndResume(journal: RetirementJournal): CompletableFuture<LpRetirementResult> =
        gateway.discoverGroupReferences(journal.groups.toSet()).thenCompose { refs ->
            require(refs.primaryGroupsComplete) { "LuckPerms stored primary-group census is incomplete; refusing deletion" }
            require(refs.groupParents.isEmpty() && refs.userParents.isEmpty() && refs.primaryGroups.isEmpty() && refs.tracks.isEmpty()) {
                "LuckPerms groups still have references; refusing deletion"
            }
            journal.deleted.forEach { group ->
                require(gateway.get(LpSubjectRef(LpSubjectType.GROUP, group)).join() == null) {
                    "LuckPerms retired group was recreated: $group"
                }
            }
            val remaining = journal.groups.filter { it !in journal.deleted }
            CompletableFuture.allOf(*remaining.map { gateway.get(LpSubjectRef(LpSubjectType.GROUP, it)) }.toTypedArray())
                .thenApply {
                    remaining.forEach { group ->
                        val snapshot = gateway.get(LpSubjectRef(LpSubjectType.GROUP, group)).join()
                        if (snapshot != null && snapshotDigest(snapshot) != journal.beforeDigests[group]) {
                            throw LpStaleReviewException("LuckPerms group changed after retirement preview: $group")
                        }
                    }
                }.thenCompose { deleteNext(journal, journal.groups.indexOfFirst { it !in journal.deleted }.takeIf { it >= 0 } ?: journal.groups.size) }
        }

    private fun verifyAndDelete(review: LpRetirementReview, idempotencyKey: String): CompletableFuture<LpRetirementResult> {
        val refsFuture = gateway.discoverGroupReferences(review.request.groups.toSet())
        return CompletableFuture.allOf(*review.request.groups.map { gateway.get(LpSubjectRef(LpSubjectType.GROUP, it)) }.toTypedArray())
            .thenCombine(refsFuture) { _, refs ->
                require(refs.primaryGroupsComplete) { "LuckPerms stored primary-group census is incomplete; refusing deletion" }
                require(refs.groupParents.isEmpty() && refs.userParents.isEmpty() && refs.primaryGroups.isEmpty() && refs.tracks.isEmpty()) {
                    "LuckPerms groups still have references; refusing deletion"
                }
                val snapshots = review.request.groups.associateWith { gateway.get(LpSubjectRef(LpSubjectType.GROUP, it)).join() }
                snapshots.forEach { (group, snapshot) ->
                    if (snapshot != null && snapshotDigest(snapshot) != review.groupDigests[group]) {
                        throw LpStaleReviewException("LuckPerms group changed after retirement preview: $group")
                    }
                }
                snapshots
            }.thenCompose { snapshots ->
                val journal = RetirementJournal(
                    reviewToken = review.reviewToken,
                    idempotencyKey = idempotencyKey,
                    groups = review.request.groups,
                    beforeDigests = review.groupDigests,
                    beforeImages = snapshots.mapValues { Gson().toJson(OpsLuckPermsJson.snapshotMap(requireNotNull(it.value))) },
                )
                store.save(journal)
                deleteNext(journal, 0)
            }
    }

    private fun deleteNext(journal: RetirementJournal, index: Int): CompletableFuture<LpRetirementResult> {
        if (index >= journal.groups.size) {
            journal.groups.forEach { group ->
                require(gateway.get(LpSubjectRef(LpSubjectType.GROUP, group)).join() == null) {
                    "LuckPerms retired group was recreated: $group"
                }
            }
            journal.state = LpRetirementState.VERIFIED
            store.save(journal)
            return CompletableFuture.completedFuture(LpRetirementResult(journal.groups, journal.state, journal.beforeDigests, journal.deleted))
        }
        val group = journal.groups[index]
        return gateway.deleteGroup(group).thenCompose {
            gateway.get(LpSubjectRef(LpSubjectType.GROUP, group)).thenCompose { remaining ->
                if (remaining != null) {
                    throw IllegalStateException("LuckPerms group deletion was not confirmed: $group")
                }
                if (group !in journal.deleted) journal.deleted += group
                store.save(journal)
                deleteNext(journal, index + 1)
            }
        }.exceptionallyCompose { failure ->
            journal.state = LpRetirementState.FAILED
            journal.failure = failure.rootMessage()
            store.save(journal)
            failedFuture(failure)
        }
    }

    private fun Throwable.rootMessage(): String = generateSequence(this) { it.cause }.last().message ?: javaClass.simpleName
    private fun <T> failedFuture(error: Throwable): CompletableFuture<T> = CompletableFuture<T>().also { it.completeExceptionally(error) }
}
