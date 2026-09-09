package ru.arc.ops.luckperms

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.PermissionHolder
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.matcher.NodeMatcher
import net.luckperms.api.track.Track
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import java.util.UUID
import java.util.concurrent.CompletableFuture

class NativeLuckPermsSubjectGateway(
    private val luckPerms: LuckPerms = LuckPermsProvider.get(),
) : LuckPermsSubjectGateway {
    companion object {
        const val MAX_STORED_USERS = 5_000
    }
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
            val permissionResult = user.cachedData.getPermissionData(options).queryPermission(request.permission)
            val inheritedGroups = user.getInheritedGroups(options)
            val sourceNode =
                permissionResult
                    .node()
                    ?.takeIf { !it.hasExpired() }
                    ?.let(LuckPermsNodeCodec::toSpec)
                    as? PermissionNodeSpec
            val directMatches = user.permissionMatches(request.permission, options).toMutableSet()
            val inheritedMatches =
                inheritedGroups
                    .flatMap { group ->
                        group.permissionMatches(request.permission, options).map { node ->
                            LpInheritedPermissionMatch(
                                group = LpSubjectRef(LpSubjectType.GROUP, group.name),
                                node = node,
                            )
                        }
                    }.toMutableSet()
            if (sourceNode != null) {
                when {
                    user.nodes.any { node -> LuckPermsNodeCodec.toSpec(node) == sourceNode } ->
                        directMatches += sourceNode
                    else ->
                        inheritedGroups
                            .filter { group ->
                                group.nodes.any { node -> LuckPermsNodeCodec.toSpec(node) == sourceNode }
                            }.forEach { group ->
                                inheritedMatches +=
                                    LpInheritedPermissionMatch(
                                        group = LpSubjectRef(LpSubjectType.GROUP, group.name),
                                        node = sourceNode,
                                    )
                            }
                }
            }
            LpPermissionCheckResult(
                result = permissionResult.result().toLpPermissionResult(),
                directMatches = directMatches.sortedBy(PermissionNodeSpec::canonicalKey),
                inheritedMatches = inheritedMatches.sortedBy { match -> "${match.group.identifier}:${match.node.canonicalKey()}" },
            )
        }

    override fun mutate(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> =
        when (ref.type) {
            LpSubjectType.GROUP -> mutateGroup(ref, additions, removals)
            LpSubjectType.USER -> mutateKnownUser(ref, additions, removals)
        }

    private fun mutateGroup(
        ref: LpSubjectRef,
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ): CompletableFuture<LpSubjectSnapshot> =
        luckPerms.groupManager
            .loadGroup(ref.identifier)
            .thenCompose { loaded ->
                loaded
                    .map { group -> CompletableFuture.completedFuture(group) }
                    .orElseGet { luckPerms.groupManager.createAndLoadGroup(ref.identifier) }
            }.thenCompose { group ->
                group.applyExactNodes(additions, removals)
                luckPerms.groupManager.saveGroup(group)
            }.thenCompose { reloadedSnapshot(ref) }

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

    override fun discoverGroupReferences(groups: Set<String>): CompletableFuture<LpGroupReferenceReport> {
        require(groups.isNotEmpty() && groups.size <= 32) { "LuckPerms reference discovery accepts 1..32 groups" }
        val targets = groups.toSet()
        val groupParents =
            luckPerms.groupManager.searchAll(NodeMatcher.type(NodeType.INHERITANCE)).thenApply { found ->
                found.entries.flatMap { (name, nodes) ->
                    nodes.mapNotNull { node ->
                        val inheritance = node as InheritanceNode
                        if (inheritance.groupName in targets) {
                            LpGroupParentReference(
                                LpSubjectRef(LpSubjectType.GROUP, name),
                                LuckPermsNodeCodec.toSpec(inheritance) as InheritanceNodeSpec,
                            )
                        } else null
                    }
                }.sortedWith(compareBy({ it.subject.identifier }, { it.node.canonicalKey() }))
            }
        val userParents =
            luckPerms.userManager.searchAll(NodeMatcher.type(NodeType.INHERITANCE)).thenApply { found ->
                found.entries.flatMap { (uuid, nodes) ->
                    nodes.mapNotNull { node ->
                        val inheritance = node as InheritanceNode
                        if (inheritance.groupName in targets) {
                            LpUserParentReference(
                                LpSubjectRef(LpSubjectType.USER, uuid.toString()),
                                LuckPermsNodeCodec.toSpec(inheritance) as InheritanceNodeSpec,
                            )
                        } else null
                    }
                }.sortedWith(compareBy({ it.subject.identifier }, { it.node.canonicalKey() }))
            }
        val users =
            luckPerms.userManager.getUniqueUsers().thenCompose { ids ->
                require(ids.size <= MAX_STORED_USERS) {
                    "LuckPerms stored user count ${ids.size} exceeds safe discovery cap $MAX_STORED_USERS"
                }
                ids.fold(CompletableFuture.completedFuture(emptyList<User>())) { loaded, id ->
                    loaded.thenCompose { current ->
                        luckPerms.userManager.loadUser(id).thenApply { user -> current + user }
                    }
                }
            }
        val tracks =
            luckPerms.trackManager.loadAllTracks().thenApply {
                luckPerms.trackManager.loadedTracks.flatMap { track ->
                    track.groups.mapIndexedNotNull { index, group ->
                        if (group in targets) LpTrackReference(track.name, index) else null
                    }
                }.sortedWith(compareBy({ it.track }, { it.index }))
            }
        return groupParents.thenCombine(userParents) { groupsFound, usersFound -> groupsFound to usersFound }
            .thenCombine(users) { (groupsFound, usersFound), loadedUsers ->
                Triple(groupsFound, usersFound, loadedUsers)
            }.thenCombine(tracks) { (groupsFound, usersFound, loadedUsers), tracksFound ->
                val storedPrimaryResult =
                    runCatching {
                        loadedUsers.mapNotNull { user ->
                            storedPrimaryGroup(user).takeIf { it in targets }?.let {
                                LpPrimaryGroupReference(LpSubjectRef(LpSubjectType.USER, user.uniqueId.toString()), it)
                            }
                        }.sortedBy { it.subject.identifier }
                    }
                LpGroupReferenceReport(
                    groups = targets.sorted(),
                    groupParents = groupsFound,
                    userParents = usersFound,
                    primaryGroups = storedPrimaryResult.getOrDefault(emptyList()),
                    tracks = tracksFound,
                    primaryGroupsComplete = storedPrimaryResult.isSuccess,
                    primaryGroupsSource = if (storedPrimaryResult.isSuccess) "stored-native" else "unavailable-external-audit-required",
                )
            }
    }

    /** Reads LuckPerms' persisted primary value through its exact implementation bridge. */
    private fun storedPrimaryGroup(user: User): String? {
        val loader = user.javaClass.classLoader
        val apiUser = Class.forName("me.lucko.luckperms.common.api.implementation.ApiUser", true, loader)
        val handle = apiUser.getMethod("cast", net.luckperms.api.model.user.User::class.java).invoke(null, user)
        val holder = handle.javaClass.getMethod("getPrimaryGroup").invoke(handle)
        val holderType = Class.forName("me.lucko.luckperms.common.model.PrimaryGroupHolder", true, loader)
        val stored = holderType.getMethod("getStoredValue").invoke(holder)
        val value = stored as? java.util.Optional<*>
            ?: throw IllegalStateException("LuckPerms stored primary-group accessor returned an invalid value")
        return value.orElse(null) as? String
    }

    override fun deleteGroup(group: String): CompletableFuture<Boolean> =
        luckPerms.groupManager.loadGroup(group).thenCompose { loaded ->
            loaded.map { value ->
                luckPerms.groupManager.deleteGroup(value).thenCompose {
                    luckPerms.groupManager.loadGroup(group).thenApply { remaining -> remaining.isEmpty }
                }
            }
                .orElseGet { CompletableFuture.completedFuture(false) }
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
    ): LpSubjectSnapshot {
        val inheritedGroups =
            if (holder is User) {
                holder
                    .getInheritedGroups(holder.queryOptions)
                    .map { group -> LpSubjectRef(LpSubjectType.GROUP, group.name) }
                    .sortedBy { it.identifier }
            } else {
                emptyList()
            }
        return LpSubjectSnapshot(
            ref,
            holder.data().toCollection().map(LuckPermsNodeCodec::toSpec).sortedBy(LpNodeSpec::canonicalKey),
            inheritedGroups,
        )
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

    private fun PermissionHolder.applyExactNodes(
        additions: Set<LpNodeSpec>,
        removals: Set<LpNodeSpec>,
    ) {
        removals.sortedBy(LpNodeSpec::canonicalKey).forEach { spec -> data().remove(LuckPermsNodeCodec.toNode(spec)) }
        additions.sortedBy(LpNodeSpec::canonicalKey).forEach { spec -> data().add(LuckPermsNodeCodec.toNode(spec)) }
    }

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
