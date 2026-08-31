package ru.arc.commandhide

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.LuckPerms
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.MetaNode
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class CommandHideBypassStoreTest :
    FreeSpec({
        "managed bypass grant" - {
            "adds a durable ownership marker and is idempotent" {
                val fixture = BypassStoreFixture()

                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, true).join() shouldBe
                    CommandHideBypassMutation.APPLIED
                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, true).join() shouldBe
                    CommandHideBypassMutation.UNCHANGED

                fixture.store.inspect(fixture.playerId, PERMISSION)?.managedGrant shouldBe true
                fixture.nodes shouldContain fixture.permissionNode(value = true)
                fixture.nodes shouldContain fixture.marker()
            }

            "allow preserves and reports an external direct deny" {
                val fixture = BypassStoreFixture()
                val deny = fixture.permissionNode(value = false)
                fixture.nodes += deny

                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, true).join() shouldBe
                    CommandHideBypassMutation.CONFLICTING_DENY

                fixture.nodes shouldContain deny
                fixture.nodes shouldNotContain fixture.permissionNode(value = true)
                fixture.nodes shouldNotContain fixture.marker()
            }

            "allow does not claim ownership of an external direct grant" {
                val fixture = BypassStoreFixture()
                val grant = fixture.permissionNode(value = true)
                fixture.nodes += grant

                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, true).join() shouldBe
                    CommandHideBypassMutation.UNMANAGED_GRANT

                fixture.nodes shouldContain grant
                fixture.nodes shouldNotContain fixture.marker()
            }

            "revoke removes only the ARC-owned permanent global grant" {
                val fixture = BypassStoreFixture()
                val temporary = fixture.temporaryPermissionNode()
                val explicitDeny = fixture.permissionNode(value = false)
                fixture.nodes += fixture.permissionNode(value = true)
                fixture.nodes += fixture.marker()
                fixture.nodes += temporary
                fixture.nodes += explicitDeny

                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, false).join() shouldBe
                    CommandHideBypassMutation.APPLIED

                fixture.store.inspect(fixture.playerId, PERMISSION)?.managedGrant shouldBe false
                fixture.nodes shouldContain temporary
                fixture.nodes shouldContain explicitDeny
                fixture.nodes shouldNotContain fixture.permissionNode(value = true)
                fixture.nodes shouldNotContain fixture.marker()
            }

            "revoke preserves an external direct grant" {
                val fixture = BypassStoreFixture()
                val grant = fixture.permissionNode(value = true)
                fixture.nodes += grant

                fixture.store.setManagedGrant(fixture.playerId, PERMISSION, false).join() shouldBe
                    CommandHideBypassMutation.UNMANAGED_GRANT

                fixture.nodes shouldContain grant
            }
        }
    }) {
    companion object {
        private const val PERMISSION = "arc.command.hide.bypass"
    }
}

private class BypassStoreFixture {
    val playerId: UUID = UUID.randomUUID()
    val nodes = linkedSetOf<Node>()
    private val nodeMap = mockk<NodeMap>()
    private val user = mockk<User>()
    private val userManager = mockk<UserManager>()
    private val luckPerms = mockk<LuckPerms>()
    private val grantNode = permissionNodeMock(value = true, expiring = false)
    private val denyNode = permissionNodeMock(value = false, expiring = false)
    private val markerNode = markerNodeMock()
    val store =
        LuckPermsCommandHideBypassStore(
            luckPerms = luckPerms,
            permissionNodeFactory = { permission, value ->
                require(permission == PERMISSION)
                if (value) grantNode else denyNode
            },
            markerFactory = { permission ->
                require(permission == PERMISSION)
                markerNode
            },
        )

    init {
        every { luckPerms.userManager } returns userManager
        every { userManager.getUser(playerId) } returns user
        every { userManager.modifyUser(playerId, any()) } answers {
            secondArg<Consumer<User>>().accept(user)
            CompletableFuture.completedFuture(null)
        }
        every { user.nodes } answers { nodes.toSet() }
        every { user.data() } returns nodeMap
        every { nodeMap.add(any()) } answers {
            nodes += firstArg<Node>()
            DataMutateResult.SUCCESS
        }
        every { nodeMap.remove(any()) } answers {
            nodes.remove(firstArg<Node>())
            DataMutateResult.SUCCESS
        }
    }

    fun permissionNode(value: Boolean): PermissionNode = if (value) grantNode else denyNode

    fun marker(): MetaNode = markerNode

    fun temporaryPermissionNode(): PermissionNode = permissionNodeMock(value = true, expiring = true)

    private fun permissionNodeMock(
        value: Boolean,
        expiring: Boolean,
    ): PermissionNode {
        val contexts = emptyContexts()
        return mockk<PermissionNode>().also { node ->
            every { node.permission } returns PERMISSION
            every { node.value } returns value
            every { node.contexts } returns contexts
            every { node.hasExpiry() } returns expiring
        }
    }

    private fun markerNodeMock(): MetaNode {
        val contexts = emptyContexts()
        return mockk<MetaNode>().also { node ->
            every { node.metaKey } returns COMMAND_HIDE_MANAGED_META_KEY
            every { node.metaValue } returns PERMISSION
            every { node.value } returns true
            every { node.contexts } returns contexts
            every { node.hasExpiry() } returns false
        }
    }

    private fun emptyContexts(): ImmutableContextSet =
        mockk<ImmutableContextSet>().also { contexts -> every { contexts.isEmpty } returns true }

    companion object {
        private const val PERMISSION = "arc.command.hide.bypass"
    }
}
