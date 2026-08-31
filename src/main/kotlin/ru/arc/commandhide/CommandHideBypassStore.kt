package ru.arc.commandhide

import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.MetaNode
import net.luckperms.api.node.types.PermissionNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

internal const val COMMAND_HIDE_MANAGED_META_KEY = "arc-commandhide-managed-bypass"

internal data class CommandHideStoredBypassState(
    val managedMarker: Boolean,
    val directGrant: Boolean,
    val directDeny: Boolean,
) {
    val managedGrant: Boolean get() = managedMarker && directGrant
}

internal enum class CommandHideBypassMutation {
    APPLIED,
    UNCHANGED,
    CONFLICTING_DENY,
    UNMANAGED_GRANT,
}

/** Persists only the bypass grant explicitly owned by `/arc commandhide`. */
internal interface CommandHideBypassStore {
    fun inspect(
        playerId: UUID,
        permission: String,
    ): CommandHideStoredBypassState?

    fun setManagedGrant(
        playerId: UUID,
        permission: String,
        enabled: Boolean,
    ): CompletableFuture<CommandHideBypassMutation>
}

internal class LuckPermsCommandHideBypassStore(
    private val luckPerms: LuckPerms,
    private val permissionNodeFactory: (String, Boolean) -> PermissionNode = ::permissionNode,
    private val markerFactory: (String) -> MetaNode = ::managedMarker,
) : CommandHideBypassStore {
    override fun inspect(
        playerId: UUID,
        permission: String,
    ): CommandHideStoredBypassState? = luckPerms.userManager.getUser(playerId)?.storedBypassState(permission)

    override fun setManagedGrant(
        playerId: UUID,
        permission: String,
        enabled: Boolean,
    ): CompletableFuture<CommandHideBypassMutation> {
        val outcome = AtomicReference<CommandHideBypassMutation>()
        return luckPerms.userManager
            .modifyUser(playerId) { user ->
                val state = user.storedBypassState(permission)
                val result =
                    if (enabled) {
                        enableManagedGrant(user, permission, state)
                    } else {
                        disableManagedGrant(user, permission, state)
                    }
                outcome.set(result)
            }.thenApply {
                checkNotNull(outcome.get()) { "LuckPerms did not execute the user mutation" }
            }
    }

    private fun enableManagedGrant(
        user: User,
        permission: String,
        state: CommandHideStoredBypassState,
    ): CommandHideBypassMutation =
        when {
            state.directDeny -> CommandHideBypassMutation.CONFLICTING_DENY
            state.directGrant && !state.managedMarker -> CommandHideBypassMutation.UNMANAGED_GRANT
            state.managedGrant -> CommandHideBypassMutation.UNCHANGED
            else -> {
                user.data().add(markerFactory(permission))
                user.data().add(permissionNodeFactory(permission, true))
                CommandHideBypassMutation.APPLIED
            }
        }

    private fun disableManagedGrant(
        user: User,
        permission: String,
        state: CommandHideStoredBypassState,
    ): CommandHideBypassMutation =
        when {
            !state.managedMarker && state.directGrant -> CommandHideBypassMutation.UNMANAGED_GRANT
            !state.managedMarker -> CommandHideBypassMutation.UNCHANGED
            else -> {
                user.data().remove(permissionNodeFactory(permission, true))
                user.data().remove(markerFactory(permission))
                CommandHideBypassMutation.APPLIED
            }
        }
}

private fun User.storedBypassState(permission: String): CommandHideStoredBypassState {
    val directPermissions =
        nodes
            .filterIsInstance<PermissionNode>()
            .filter { it.permission == permission && it.isPermanentGlobal() }
    return CommandHideStoredBypassState(
        managedMarker =
            nodes
                .filterIsInstance<MetaNode>()
                .any {
                    it.metaKey == COMMAND_HIDE_MANAGED_META_KEY &&
                        it.metaValue == permission &&
                        it.value &&
                        it.isPermanentGlobal()
                },
        directGrant = directPermissions.any { it.value },
        directDeny = directPermissions.any { !it.value },
    )
}

private fun Node.isPermanentGlobal(): Boolean = contexts.isEmpty && !hasExpiry()

private fun permissionNode(
    permission: String,
    value: Boolean,
): PermissionNode = PermissionNode.builder(permission).value(value).build()

private fun managedMarker(permission: String): MetaNode =
    MetaNode.builder(COMMAND_HIDE_MANAGED_META_KEY, permission).build()
