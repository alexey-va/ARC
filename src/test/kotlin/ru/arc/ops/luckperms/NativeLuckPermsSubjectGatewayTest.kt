package ru.arc.ops.luckperms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.cacheddata.CachedPermissionData
import net.luckperms.api.cacheddata.Result
import net.luckperms.api.context.ContextManager
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ContextSetFactory
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.group.GroupManager
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.PermissionNode
import net.luckperms.api.query.QueryMode
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import java.lang.reflect.Field
import java.time.Instant
import java.util.IdentityHashMap
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class NativeLuckPermsSubjectGatewayTest : FreeSpec({
    val fixture = NativeGatewayFixture()

    beforeTest {
        fixture.install()
    }
    afterTest {
        fixture.uninstall()
    }

    "native LuckPerms subject gateway" - {
        "lists reloaded groups as sorted direct snapshots" {
            fixture.putGroup("zeta", fixture.node(PermissionNodeSpec("example.zeta")))
            fixture.putGroup("alpha", fixture.node(PermissionNodeSpec("example.alpha")))

            fixture.gateway.listGroups().join().map { it.subject.identifier }.shouldContainExactly("alpha", "zeta")
        }

        "loads exact direct group nodes" {
            val node = fixture.node(PermissionNodeSpec("example.group", contexts = LpContextSet(mapOf("server" to listOf("spawn")))))
            fixture.putGroup("builder", node)

            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.GROUP, "builder")).join()!!.nodes
                .shouldContainExactly(PermissionNodeSpec("example.group", contexts = LpContextSet(mapOf("server" to listOf("spawn")))))
        }

        "loads snapshots from NORMAL persisted nodes only" {
            val normal = PermissionNodeSpec("example.persisted", value = true)
            val transient = PermissionNodeSpec("example.persisted", value = false)
            val groupUserId = UUID.fromString("00000000-0000-0000-0000-000000000111")
            val user = PermissionNodeSpec("example.user-persisted", value = true)
            val userTransient = PermissionNodeSpec("example.user-persisted", value = false)
            fixture.putGroupWithTransient("builder", setOf(fixture.node(normal)), setOf(fixture.node(transient)))
            fixture.putUserWithTransient(
                groupUserId,
                "PersistedUser",
                normalNodes = setOf(fixture.node(user)),
                transientNodes = setOf(fixture.node(userTransient)),
            )

            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.GROUP, "builder")).join()!!.nodes
                .shouldContainExactly(normal)
            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.USER, groupUserId.toString())).join()!!.nodes
                .shouldContainExactly(user)
        }

        "loads a UUID-pinned user only when LuckPerms knows that UUID" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000101")
            fixture.putUser(userId, "ExactUser", fixture.node(PermissionNodeSpec("example.user")))

            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.USER, userId.toString())).join()!!.nodes
                .shouldContainExactly(PermissionNodeSpec("example.user"))

            fixture.gateway.get(LpSubjectRef(LpSubjectType.USER, "00000000-0000-0000-0000-000000000102")).join().shouldBeNull()
        }

        "user snapshots include effective inherited groups" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000103")
            fixture.putGroup("member")
            fixture.putGroup("builder")
            fixture.putUser(
                userId,
                "GroupedUser",
                inheritedGroups = setOf("member", "builder"),
            )

            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.USER, userId.toString()))
                .join()!!
                .inheritedGroups
                .map { it.identifier }
                .shouldContainExactly("builder", "member")
        }

        "resolves a player name only through LuckPerms lookupUniqueId" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000201")
            fixture.lookup("KnownName", userId)

            fixture.gateway.lookupUser("KnownName").join() shouldBe LpUserIdentity(userId, "KnownName")
            fixture.gateway.lookupUser("UnknownName").join().shouldBeNull()
        }

        "separates direct and inherited exact permission matches while using the contextual effective result" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000301")
            val direct = fixture.node(PermissionNodeSpec("example.build", value = false))
            val inherited = fixture.node(PermissionNodeSpec("example.build", contexts = LpContextSet(mapOf("server" to listOf("spawn")))))
            fixture.putGroup("builder", inherited)
            fixture.putUser(userId, "BuilderUser", direct, inheritedGroups = setOf("builder"), effectiveResult = Tristate.FALSE)

            fixture.gateway
                .check(
                    LpPermissionCheckRequest(
                        userId = userId,
                        permission = "example.build",
                        contexts = LpContextSet(mapOf("server" to listOf("spawn"))),
                    ),
                ).join() shouldBe
                LpPermissionCheckResult(
                    result = LpPermissionResult.FALSE,
                    directMatches = listOf(PermissionNodeSpec("example.build", value = false)),
                    inheritedMatches =
                        listOf(
                            LpInheritedPermissionMatch(
                                group = LpSubjectRef(LpSubjectType.GROUP, "builder"),
                                node = PermissionNodeSpec(
                                    "example.build",
                                    contexts = LpContextSet(mapOf("server" to listOf("spawn"))),
                                ),
                            ),
                        ),
                )
        }

        "excludes expired direct and inherited nodes from effective permission sources" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000302")
            val expiredAt = Instant.now().minusSeconds(60)
            val expiredDirect = fixture.node(PermissionNodeSpec("example.expired", expiresAt = expiredAt))
            val expiredInherited = fixture.node(PermissionNodeSpec("example.expired", expiresAt = expiredAt))
            fixture.putGroup("expired-parent", expiredInherited)
            fixture.putUser(
                userId,
                "ExpiredUser",
                expiredDirect,
                inheritedGroups = setOf("expired-parent"),
                effectiveResult = Tristate.UNDEFINED,
            )

            fixture.gateway
                .check(LpPermissionCheckRequest(userId, "example.expired"))
                .join() shouldBe LpPermissionCheckResult(LpPermissionResult.UNDEFINED, emptyList(), emptyList())
        }

        "explains wildcard permission checks with the actual LuckPerms source node" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000304")
            val wildcardSpec = PermissionNodeSpec("example.*")
            val wildcard = fixture.node(wildcardSpec)
            fixture.putUser(
                userId,
                "WildcardUser",
                wildcard,
                effectiveResult = Tristate.TRUE,
                effectiveSource = wildcard,
            )

            fixture.gateway
                .check(LpPermissionCheckRequest(userId, "example.build"))
                .join() shouldBe
                LpPermissionCheckResult(
                    result = LpPermissionResult.TRUE,
                    directMatches = listOf(wildcardSpec),
                    inheritedMatches = emptyList(),
                )
        }

        "uses the explicit context for direct global and inherited permission sources" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000303")
            val spawn = PermissionNodeSpec("example.context", contexts = LpContextSet(mapOf("server" to listOf("spawn"))))
            val survival = PermissionNodeSpec("example.context", contexts = LpContextSet(mapOf("server" to listOf("survival"))))
            val global = PermissionNodeSpec("example.context")
            val inheritedSpawn = PermissionNodeSpec("example.context", contexts = LpContextSet(mapOf("server" to listOf("spawn"))))
            fixture.putGroup("context-parent", fixture.node(inheritedSpawn))
            fixture.putUser(
                userId,
                "ContextUser",
                fixture.node(spawn),
                fixture.node(survival),
                fixture.node(global),
                inheritedGroups = setOf("context-parent"),
                effectiveResult = Tristate.TRUE,
            )

            fixture.gateway.check(LpPermissionCheckRequest(userId, "example.context", LpContextSet(mapOf("server" to listOf("spawn"))))).join() shouldBe
                LpPermissionCheckResult(
                    result = LpPermissionResult.TRUE,
                    directMatches = listOf(spawn, global),
                    inheritedMatches = listOf(LpInheritedPermissionMatch(LpSubjectRef(LpSubjectType.GROUP, "context-parent"), inheritedSpawn)),
                )
            fixture.lastPermissionContext shouldBe LpContextSet(mapOf("server" to listOf("spawn")))
            fixture.lastInheritedGroupContext shouldBe LpContextSet(mapOf("server" to listOf("spawn")))

            fixture.gateway
                .check(LpPermissionCheckRequest(userId, "example.context", LpContextSet(mapOf("server" to listOf("survival")))))
                .join() shouldBe LpPermissionCheckResult(LpPermissionResult.TRUE, listOf(survival, global), emptyList())
            fixture.lastPermissionContext shouldBe LpContextSet(mapOf("server" to listOf("survival")))
            fixture.lastInheritedGroupContext shouldBe LpContextSet(mapOf("server" to listOf("survival")))
        }

        "creates a missing group and returns the reloaded exact node snapshot after mutation" {
            val addition = PermissionNodeSpec("example.add")
            val removal = PermissionNodeSpec("example.remove")
            fixture.node(addition)
            fixture.putGroup("existing", fixture.node(removal))

            fixture.gateway
                .mutate(
                    LpSubjectRef(LpSubjectType.GROUP, "existing"),
                    additions = setOf(addition),
                    removals = setOf(removal),
                ).join().nodes.shouldContainExactly(addition)

            fixture.gateway
                .mutate(
                    LpSubjectRef(LpSubjectType.GROUP, "new-group"),
                    additions = setOf(addition),
                    removals = emptySet(),
                ).join().nodes.shouldContainExactly(addition)
        }

        "rejects mutation of an unknown UUID instead of manufacturing a Bukkit offline identity" {
            val unknown = LpSubjectRef(LpSubjectType.USER, "00000000-0000-0000-0000-000000000401")

            shouldThrow<Exception> {
                fixture.gateway.mutate(unknown, setOf(PermissionNodeSpec("example.add")), emptySet()).join()
            }
        }
    }
})

private class NativeGatewayFixture {
    private val providerField: Field =
        LuckPermsProvider::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val luckPerms = mockk<LuckPerms>()
    private val groupManager = mockk<GroupManager>()
    private val userManager = mockk<UserManager>()
    private val contextManager = mockk<ContextManager>()
    private val contextSetFactory = mockk<ContextSetFactory>()
    private val contextSetBuilder = mockk<ImmutableContextSet.Builder>()
    private val requestContextSet = mockk<ImmutableContextSet>()
    private val queryBuilder = mockk<QueryOptions.Builder>()
    private val queryOptions = mockk<QueryOptions>()
    private val groups = linkedMapOf<String, GatewayGroup>()
    private val users = linkedMapOf<UUID, GatewayUser>()
    private val usernames = linkedMapOf<UUID, String>()
    private val nameLookup = linkedMapOf<String, UUID>()
    private val contextSpecs = IdentityHashMap<ContextSet, LpContextSet>()
    private val requestContextEntries = mutableListOf<Pair<String, String>>()
    private var queryContext: LpContextSet? = null

    var lastPermissionContext: LpContextSet? = null
    var lastInheritedGroupContext: LpContextSet? = null

    lateinit var gateway: NativeLuckPermsSubjectGateway

    fun install() {
        mockkObject(LuckPermsNodeCodec)
        every { luckPerms.groupManager } returns groupManager
        every { luckPerms.userManager } returns userManager
        every { luckPerms.contextManager } returns contextManager
        every { contextManager.contextSetFactory } returns contextSetFactory
        every { contextSetFactory.immutableBuilder() } answers {
            requestContextEntries.clear()
            contextSetBuilder
        }
        every { contextSetBuilder.add(any(), any()) } answers {
            requestContextEntries += firstArg<String>() to secondArg<String>()
            contextSetBuilder
        }
        every { contextSetBuilder.build() } answers {
            contextSpecs[requestContextSet] = LpContextSet(requestContextEntries.groupBy({ it.first }, { it.second }))
            requestContextSet
        }
        every { contextManager.queryOptionsBuilder(QueryMode.CONTEXTUAL) } returns queryBuilder
        every { queryBuilder.context(any()) } answers {
            queryContext = contextSpecs[firstArg<ContextSet>()]
            queryBuilder
        }
        every { queryBuilder.build() } returns queryOptions
        every { queryOptions.satisfies(any()) } answers {
            contextMatches(requireNotNull(queryContext), contextSpecs.getValue(firstArg()))
        }

        every { groupManager.loadAllGroups() } returns CompletableFuture.completedFuture(null)
        every { groupManager.loadedGroups } answers { groups.values.map { it.group }.toSet() }
        every { groupManager.loadGroup(any()) } answers {
            CompletableFuture.completedFuture(Optional.ofNullable(groups[firstArg<String>()]?.group))
        }
        every { groupManager.modifyGroup(any(), any()) } answers {
            val name = firstArg<String>()
            val group = groups.getOrPut(name) { GatewayGroup(name) }.group
            @Suppress("UNCHECKED_CAST")
            (secondArg<Consumer<*>>() as Consumer<Group>).accept(group)
            CompletableFuture.completedFuture(null)
        }

        every { userManager.lookupUniqueId(any()) } answers {
            CompletableFuture.completedFuture(nameLookup[firstArg<String>()])
        }
        every { userManager.lookupUsername(any()) } answers {
            CompletableFuture.completedFuture(usernames[firstArg<UUID>()])
        }
        every { userManager.loadUser(any()) } answers {
            CompletableFuture.completedFuture(users.getValue(firstArg<UUID>()).user)
        }
        every { userManager.modifyUser(any(), any()) } answers {
            val user = users.getValue(firstArg())
            @Suppress("UNCHECKED_CAST")
            (secondArg<Consumer<*>>() as Consumer<User>).accept(user.user)
            CompletableFuture.completedFuture(null)
        }
        providerField.set(null, luckPerms)
        gateway = NativeLuckPermsSubjectGateway()
    }

    fun uninstall() {
        providerField.set(null, null)
        unmockkObject(LuckPermsNodeCodec)
        groups.clear()
        users.clear()
        usernames.clear()
        nameLookup.clear()
        contextSpecs.clear()
        requestContextEntries.clear()
        queryContext = null
        lastPermissionContext = null
        lastInheritedGroupContext = null
    }

    fun node(spec: LpNodeSpec): Node = mockk<PermissionNode>().also { node ->
        val nodeContextSet = mockk<ImmutableContextSet>()
        contextSpecs[nodeContextSet] = spec.contexts
        every { node.permission } returns (spec as PermissionNodeSpec).permission
        every { node.contexts } returns nodeContextSet
        every { node.hasExpired() } returns (spec.expiresAt?.isBefore(Instant.now()) ?: false)
        every { LuckPermsNodeCodec.toSpec(node) } returns spec
        every { LuckPermsNodeCodec.toNode(spec) } returns node
    }

    fun putGroup(name: String, vararg nodes: Node) {
        groups[name] = GatewayGroup(name, nodes.toMutableSet())
    }

    fun putGroupWithTransient(
        name: String,
        normalNodes: Set<Node>,
        transientNodes: Set<Node>,
    ) {
        groups[name] = GatewayGroup(name, normalNodes.toMutableSet(), transientNodes.toMutableSet())
    }

    fun putUser(
        uuid: UUID,
        username: String,
        vararg nodes: Node,
        inheritedGroups: Set<String> = emptySet(),
        effectiveResult: Tristate = Tristate.UNDEFINED,
        effectiveSource: Node? = null,
    ) {
        usernames[uuid] = username
        nameLookup[username] = uuid
        users[uuid] = GatewayUser(uuid, nodes.toMutableSet(), inheritedGroups, effectiveResult, effectiveSource = effectiveSource)
    }

    fun putUserWithTransient(
        uuid: UUID,
        username: String,
        normalNodes: Set<Node>,
        transientNodes: Set<Node>,
    ) {
        usernames[uuid] = username
        nameLookup[username] = uuid
        users[uuid] = GatewayUser(uuid, normalNodes.toMutableSet(), emptySet(), Tristate.UNDEFINED, transientNodes.toMutableSet())
    }

    fun lookup(name: String, uuid: UUID) {
        nameLookup[name] = uuid
    }

    private inner class GatewayGroup(
        name: String,
        private val normalNodes: MutableSet<Node> = linkedSetOf(),
        private val transientNodes: MutableSet<Node> = linkedSetOf(),
    ) {
        val group = mockk<Group>()
        private val nodeMap = mockk<NodeMap>()

        init {
            every { group.name } returns name
            every { group.nodes } answers { normalNodes.plus(transientNodes).toSet() }
            every { group.data() } returns nodeMap
            every { nodeMap.toCollection() } answers { normalNodes.toSet() }
            every { nodeMap.add(any()) } answers {
                normalNodes += firstArg<Node>()
                DataMutateResult.SUCCESS
            }
            every { nodeMap.remove(any()) } answers {
                normalNodes -= firstArg<Node>()
                DataMutateResult.SUCCESS
            }
        }
    }

    private inner class GatewayUser(
        uuid: UUID,
        private val nodes: MutableSet<Node>,
        inheritedGroups: Set<String>,
        effectiveResult: Tristate,
        private val transientNodes: MutableSet<Node> = linkedSetOf(),
        effectiveSource: Node? = null,
    ) {
        val user = mockk<User>()
        private val nodeMap = mockk<NodeMap>()
        private val cachedData = mockk<CachedDataManager>()
        private val permissionData = mockk<CachedPermissionData>()
        private val permissionResult = mockk<Result<Tristate, Node>>()

        init {
            every { user.uniqueId } returns uuid
            every { user.queryOptions } returns queryOptions
            every { user.nodes } answers { nodes.plus(transientNodes).toSet() }
            every { user.data() } returns nodeMap
            every { nodeMap.toCollection() } answers { nodes.toSet() }
            every { user.getInheritedGroups(any()) } answers {
                lastInheritedGroupContext = queryContext
                inheritedGroups.mapNotNull { groups[it]?.group }.toSet()
            }
            every { user.cachedData } returns cachedData
            every { cachedData.getPermissionData(any()) } answers {
                lastPermissionContext = queryContext
                permissionData
            }
            every { permissionData.checkPermission(any()) } returns effectiveResult
            every { permissionData.queryPermission(any()) } returns permissionResult
            every { permissionResult.result() } returns effectiveResult
            every { permissionResult.node() } returns effectiveSource
            every { nodeMap.add(any()) } answers {
                nodes += firstArg<Node>()
                DataMutateResult.SUCCESS
            }
            every { nodeMap.remove(any()) } answers {
                nodes -= firstArg<Node>()
                DataMutateResult.SUCCESS
            }
        }
    }

    private fun contextMatches(
        requested: LpContextSet,
        required: LpContextSet,
    ): Boolean =
        required.asMap().all { (key, requiredValues) ->
            requested.asMap()[key].orEmpty().any(requiredValues::contains)
        }
}
