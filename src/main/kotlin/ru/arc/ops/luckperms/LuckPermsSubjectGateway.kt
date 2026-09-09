package ru.arc.ops.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture

interface LuckPermsSubjectGateway {
    fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>>

    /** Returns only nodes directly assigned to the requested subject. */
    fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?>

    fun lookupUser(name: String): CompletableFuture<LpUserIdentity?>

    fun check(request: LpPermissionCheckRequest): CompletableFuture<LpPermissionCheckResult?>

    fun mutate(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot>

    fun discoverGroupReferences(groups: Set<String>): CompletableFuture<LpGroupReferenceReport> =
        CompletableFuture.failedFuture(UnsupportedOperationException("LuckPerms reference discovery is unavailable"))

    fun deleteGroup(group: String): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(UnsupportedOperationException("LuckPerms group deletion is unavailable"))

}

data class LpUserIdentity(
    val uuid: UUID,
    val username: String,
) {
    init {
        require(username.isNotBlank()) { "LuckPerms username must not be blank" }
    }
}

data class LpPermissionCheckRequest(
    val userId: UUID,
    val permission: String,
    val contexts: LpContextSet = LpContextSet(),
) {
    init {
        require(permission.isNotBlank()) { "LuckPerms permission must not be blank" }
    }
}

enum class LpPermissionResult {
    TRUE,
    FALSE,
    UNDEFINED,
}

data class LpInheritedPermissionMatch(
    val group: LpSubjectRef,
    val node: PermissionNodeSpec,
) {
    init {
        require(group.type == LpSubjectType.GROUP) { "Inherited LuckPerms permission source must be a group" }
    }
}

data class LpPermissionCheckResult(
    val result: LpPermissionResult,
    val directMatches: List<PermissionNodeSpec>,
    val inheritedMatches: List<LpInheritedPermissionMatch>,
)
