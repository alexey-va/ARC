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
import net.luckperms.api.context.ContextManager
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

        "loads a UUID-pinned user only when LuckPerms knows that UUID" {
            val userId = UUID.fromString("00000000-0000-0000-0000-000000000101")
            fixture.putUser(userId, "ExactUser", fixture.node(PermissionNodeSpec("example.user")))

            fixture.gateway
                .get(LpSubjectRef(LpSubjectType.USER, userId.toString())).join()!!.nodes
                .shouldContainExactly(PermissionNodeSpec("example.user"))

            fixture.gateway.get(LpSubjectRef(LpSubjectType.USER, "00000000-0000-0000-0000-000000000102")).join().shouldBeNull()
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
    private val contextSet = mockk<ImmutableContextSet>()
    private val queryBuilder = mockk<QueryOptions.Builder>()
    private val queryOptions = mockk<QueryOptions>()
    private val groups = linkedMapOf<String, GatewayGroup>()
    private val users = linkedMapOf<UUID, GatewayUser>()
    private val usernames = linkedMapOf<UUID, String>()
    private val nameLookup = linkedMapOf<String, UUID>()

    lateinit var gateway: NativeLuckPermsSubjectGateway

    fun install() {
        mockkObject(LuckPermsNodeCodec)
        every { luckPerms.groupManager } returns groupManager
        every { luckPerms.userManager } returns userManager
        every { luckPerms.contextManager } returns contextManager
        every { contextManager.contextSetFactory } returns contextSetFactory
        every { contextSetFactory.immutableBuilder() } returns contextSetBuilder
        every { contextSetBuilder.add(any(), any()) } returns contextSetBuilder
        every { contextSetBuilder.build() } returns contextSet
        every { contextManager.queryOptionsBuilder(QueryMode.CONTEXTUAL) } returns queryBuilder
        every { queryBuilder.context(any()) } returns queryBuilder
        every { queryBuilder.build() } returns queryOptions
        every { queryOptions.satisfies(any()) } returns true

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
    }

    fun node(spec: LpNodeSpec): Node = mockk<PermissionNode>().also { node ->
        every { node.permission } returns (spec as PermissionNodeSpec).permission
        every { node.contexts } returns contextSet
        every { LuckPermsNodeCodec.toSpec(node) } returns spec
        every { LuckPermsNodeCodec.toNode(spec) } returns node
    }

    fun putGroup(name: String, vararg nodes: Node) {
        groups[name] = GatewayGroup(name, nodes.toMutableSet())
    }

    fun putUser(
        uuid: UUID,
        username: String,
        vararg nodes: Node,
        inheritedGroups: Set<String> = emptySet(),
        effectiveResult: Tristate = Tristate.UNDEFINED,
    ) {
        usernames[uuid] = username
        nameLookup[username] = uuid
        users[uuid] = GatewayUser(uuid, nodes.toMutableSet(), inheritedGroups, effectiveResult)
    }

    fun lookup(name: String, uuid: UUID) {
        nameLookup[name] = uuid
    }

    private inner class GatewayGroup(
        name: String,
        private val nodes: MutableSet<Node> = linkedSetOf(),
    ) {
        val group = mockk<Group>()
        private val nodeMap = mockk<NodeMap>()

        init {
            every { group.name } returns name
            every { group.nodes } answers { nodes.toSet() }
            every { group.data() } returns nodeMap
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

    private inner class GatewayUser(
        uuid: UUID,
        private val nodes: MutableSet<Node>,
        inheritedGroups: Set<String>,
        effectiveResult: Tristate,
    ) {
        val user = mockk<User>()
        private val nodeMap = mockk<NodeMap>()
        private val cachedData = mockk<CachedDataManager>()
        private val permissionData = mockk<CachedPermissionData>()

        init {
            every { user.uniqueId } returns uuid
            every { user.nodes } answers { nodes.toSet() }
            every { user.data() } returns nodeMap
            every { user.getInheritedGroups(any()) } answers {
                inheritedGroups.mapNotNull { groups[it]?.group }.toSet()
            }
            every { user.cachedData } returns cachedData
            every { cachedData.getPermissionData(any()) } returns permissionData
            every { permissionData.checkPermission(any()) } returns effectiveResult
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
}
