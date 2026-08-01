package ru.arc.ops.luckperms

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.PermissionHolder
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import java.util.UUID
import java.util.concurrent.CompletableFuture

class NativeLuckPermsSubjectGateway(
    private val luckPerms: LuckPerms = LuckPermsProvider.get(),
) : LuckPermsSubjectGateway {
    override fun listGroups(): CompletableFuture<List<LpSubjectSnapshot>> =
        luckPerms.groupManager.loadAllGroups().thenApply {
            luckPerms.groupManager.loadedGroups
                .map { group -> snapshot(LpSubjectRef(LpSubjectType.GROUP, group.name), group) }
                .sortedBy { it.subject.identifier }
        }

    override fun get(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot?> =
        when (ref.type) {
            LpSubjectType.GROUP ->
                luckPerms.groupManager
                    .loadGroup(ref.identifier)
                    .thenApply { group -> group.orElse(null)?.let { snapshot(ref, it) } }
            LpSubjectType.USER -> loadKnownUser(UUID.fromString(ref.identifier)).thenApply { user -> user?.let { snapshot(ref, it) } }
        }

    override fun lookupUser(name: String): CompletableFuture<LpUserIdentity?> {
        require(name.isNotBlank()) { "LuckPerms username must not be blank" }
        return luckPerms.userManager.lookupUniqueId(name).thenApply { uuid -> uuid?.let { LpUserIdentity(it, name) } }
    }

    override fun check(request: LpPermissionCheckRequest): CompletableFuture<LpPermissionCheckResult?> =
        loadKnownUser(request.userId).thenApply { user ->
            user ?: return@thenApply null
            val options = QueryOptions.contextual(request.contexts.toLuckPermsContextSet())
            val directMatches = user.permissionMatches(request.permission, options)
            val inheritedMatches =
                user
                    .getInheritedGroups(options)
                    .flatMap { group ->
                        group.permissionMatches(request.permission, options).map { node ->
                            LpInheritedPermissionMatch(
                                group = LpSubjectRef(LpSubjectType.GROUP, group.name),
                                node = node,
                            )
                        }
                    }.sortedBy { match -> "${match.group.identifier}:${match.node.canonicalKey()}" }
            LpPermissionCheckResult(
                result = user.cachedData.getPermissionData(options).checkPermission(request.permission).toLpPermissionResult(),
                directMatches = directMatches,
                inheritedMatches = inheritedMatches,
            )
        }

    override fun mutate(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> =
        when (ref.type) {
            LpSubjectType.GROUP ->
                luckPerms.groupManager
                    .modifyGroup(ref.identifier) { group -> group.applyExactNodes(additions, removals) }
                    .thenCompose { reloadedSnapshot(ref) }
            LpSubjectType.USER -> mutateKnownUser(ref, additions, removals)
        }

    private fun mutateKnownUser(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> {
        val uuid = UUID.fromString(ref.identifier)
        return loadKnownUser(uuid).thenCompose { user ->
            if (user == null) {
                CompletableFuture.failedFuture(NoSuchElementException("Unknown LuckPerms user UUID: $uuid"))
            } else {
                luckPerms.userManager
                    .modifyUser(uuid) { current -> current.applyExactNodes(additions, removals) }
                    .thenCompose { reloadedSnapshot(ref) }
            }
        }
    }

    private fun reloadedSnapshot(ref: LpSubjectRef): CompletableFuture<LpSubjectSnapshot> =
        get(ref).thenApply { snapshot -> requireNotNull(snapshot) { "LuckPerms subject disappeared after mutation: ${ref.identifier}" } }

    private fun loadKnownUser(uuid: UUID): CompletableFuture<User?> =
        luckPerms.userManager.lookupUsername(uuid).thenCompose { username ->
            if (username == null) {
                CompletableFuture.completedFuture(null)
            } else {
                luckPerms.userManager.loadUser(uuid).thenApply<User?> { it }
            }
        }

    private fun snapshot(
        ref: LpSubjectRef,
        holder: PermissionHolder,
    ): LpSubjectSnapshot =
        LpSubjectSnapshot(ref, holder.data().toCollection().map(LuckPermsNodeCodec::toSpec).sortedBy(LpNodeSpec::canonicalKey))

    private fun PermissionHolder.applyExactNodes(
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ) {
        additions.sortedBy(LpNodeSpec::canonicalKey).forEach { spec -> data().add(LuckPermsNodeCodec.toNode(spec)) }
        removals.sortedBy(LpNodeSpec::canonicalKey).forEach { spec -> data().remove(LuckPermsNodeCodec.toNode(spec)) }
    }

    private fun PermissionHolder.permissionMatches(
        permission: String,
        options: QueryOptions,
    ): List<PermissionNodeSpec> =
        nodes
            .filterIsInstance<PermissionNode>()
            .filter { node -> !node.hasExpired() && node.permission == permission && options.satisfies(node.contexts) }
            .map(LuckPermsNodeCodec::toSpec)
            .filterIsInstance<PermissionNodeSpec>()
            .sortedBy(PermissionNodeSpec::canonicalKey)
}

private fun LpContextSet.toLuckPermsContextSet(): ContextSet =
    ImmutableContextSet.builder().also { builder ->
        asMap().forEach { (key, values) -> values.forEach { value -> builder.add(key, value) } }
    }.build()

private fun Tristate.toLpPermissionResult(): LpPermissionResult =
    when (this) {
        Tristate.TRUE -> LpPermissionResult.TRUE
        Tristate.FALSE -> LpPermissionResult.FALSE
        Tristate.UNDEFINED -> LpPermissionResult.UNDEFINED
    }
