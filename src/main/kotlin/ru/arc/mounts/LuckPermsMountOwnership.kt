package ru.arc.mounts

import net.luckperms.api.LuckPerms
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LuckPermsMountOwnership(private val luckPerms: LuckPerms) : MountOwnership {
    override fun profile(subject: MountPermissionSubject, mount: MountDefinition): MountProfile {
        val level =
            (1..mount.maxLevel)
                .filter { subject.hasPermission(mount.levelPermission(it)) }
                .maxOrNull() ?: 0
        val glowOwned = subject.hasPermission(mount.glowPermission)
        val glowDisabled = hasDirectPositivePermission(subject.uniqueId, mount.glowDisabledPermission)
        return MountProfile(level, glowOwned, glowDisabled)
    }

    override fun grantLevel(playerId: UUID, mount: MountDefinition, level: Int): CompletableFuture<Void> {
        require(level in 1..mount.maxLevel) { "Invalid ${mount.id} level: $level" }
        return luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.levelPermission(level)))
        }
    }

    override fun grantGlow(playerId: UUID, mount: MountDefinition): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            user.data().add(permission(mount.glowPermission))
            removePermission(user.data()::remove, user.nodes, mount.glowDisabledPermission)
        }

    override fun setGlowEnabled(playerId: UUID, mount: MountDefinition, enabled: Boolean): CompletableFuture<Void> =
        luckPerms.userManager.modifyUser(playerId) { user ->
            removePermission(user.data()::remove, user.nodes, mount.glowDisabledPermission)
            if (!enabled) user.data().add(permission(mount.glowDisabledPermission))
        }

    override fun resolveUniqueId(playerName: String): CompletableFuture<UUID?> =
        luckPerms.userManager.lookupUniqueId(playerName)

    private fun removePermission(remove: (Node) -> Unit, nodes: Collection<Node>, permission: String) {
        nodes.filterIsInstance<PermissionNode>()
            .filter { it.permission == permission }
            .forEach(remove)
    }

    private fun hasDirectPositivePermission(playerId: UUID, permission: String): Boolean =
        hasDirectPositivePermission(luckPerms.userManager.getUser(playerId)?.nodes.orEmpty(), permission)

    private fun permission(name: String): PermissionNode = PermissionNode.builder(name).value(true).build()
}

internal fun hasDirectPositivePermission(nodes: Collection<Node>, permission: String): Boolean =
    nodes.filterIsInstance<PermissionNode>().any { it.permission == permission && it.value }
