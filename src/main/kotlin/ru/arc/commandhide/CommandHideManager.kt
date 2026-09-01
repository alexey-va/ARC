package ru.arc.commandhide

import net.luckperms.api.LuckPerms
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal data class CommandHideBypassState(
    val permission: String,
    val managedGrant: Boolean,
    val unmanagedDirectGrant: Boolean,
    val effectiveBypass: Boolean,
)

internal sealed interface CommandHideAdminResult {
    data class State(
        val value: CommandHideBypassState,
        val commandTreeUpdated: Boolean = true,
    ) : CommandHideAdminResult

    data object Busy : CommandHideAdminResult

    data object ModuleDisabled : CommandHideAdminResult

    data object BypassDisabled : CommandHideAdminResult

    data object ProviderUnavailable : CommandHideAdminResult

    data object ConflictingDeny : CommandHideAdminResult

    data object UnmanagedGrant : CommandHideAdminResult

    data object TargetOffline : CommandHideAdminResult

    data object Failed : CommandHideAdminResult
}

internal interface CommandHideAdminController {
    fun status(player: Player): CommandHideAdminResult

    fun setBypass(
        player: Player,
        enabled: Boolean,
        callback: (CommandHideAdminResult) -> Unit,
    )
}

private data class InFlightCommandHideMutation(
    val permission: String,
    val enabled: Boolean,
    val future: CompletableFuture<CommandHideBypassMutation>,
)

internal object CommandHideManager : CommandHideAdminController {
    private var policies: CommandHidePolicyResolver? = null
    private var listener: CommandHideListener? = null
    private var bypassStore: CommandHideBypassStore? = null
    @Volatile
    private var tasks: LifecycleTaskScope? = null
    private val mutatingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val inFlightMutations = ConcurrentHashMap<UUID, InFlightCommandHideMutation>()

    fun init() {
        check(policies == null && listener == null && tasks == null) { "CommandHideManager is already initialized" }

        val resolver = CommandHidePolicyResolver(CommandHideModuleConfig.load(ARC.instance.dataPath))
        val createdListener =
            CommandHideListener(resolver) { task ->
                Bukkit.getScheduler().runTask(ARC.instance, task)
            }
        val luckPerms =
            if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                ARC.instance.server.servicesManager.load(LuckPerms::class.java)
            } else {
                null
            }
        policies = resolver
        listener = createdListener
        bypassStore = luckPerms?.let(::LuckPermsCommandHideBypassStore)
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(createdListener, ARC.instance)
        if (luckPerms == null) {
            warn("CommandHide admin bypass management is unavailable because LuckPerms API is not loaded")
        }
        logLoaded(resolver.currentSnapshot(), "loaded")
    }

    fun reload() {
        val resolver = checkNotNull(policies) { "CommandHideManager is not initialized" }
        val config = CommandHideModuleConfig.load(ARC.instance.dataPath)
        val current = resolver.currentSnapshot()
        val candidate = CommandHideSnapshot.compile(config)
        if (mutatingPlayers.isNotEmpty() && candidate.bypassPermission != current.bypassPermission) {
            warn(
                "CommandHide reload deferred because {} bypass mutations are still running and bypass-permission changed",
                mutatingPlayers.size,
            )
            return
        }
        val replacement = resolver.reload(config)
        Bukkit.getOnlinePlayers().forEach(PlayerCommandTreeUpdater::update)
        logLoaded(replacement, "reloaded")
    }

    fun shutdown() {
        listener?.let(HandlerList::unregisterAll)
        listener = null
        val closingTasks = tasks
        tasks = null
        closingTasks?.close()
        bypassStore = null
        policies?.clear()
        policies = null
        inFlightMutations.entries
            .filter { it.value.future.isDone }
            .forEach { clearMutation(it.key, it.value) }
    }

    override fun status(player: Player): CommandHideAdminResult {
        val snapshot = policies?.currentSnapshot() ?: return CommandHideAdminResult.Failed
        if (!snapshot.enabled) return CommandHideAdminResult.ModuleDisabled
        val permission = snapshot.bypassPermission
        if (permission.isBlank()) return CommandHideAdminResult.BypassDisabled
        val stored = bypassStore?.inspect(player.uniqueId, permission)
            ?: return if (bypassStore == null) {
                CommandHideAdminResult.ProviderUnavailable
            } else {
                CommandHideAdminResult.Failed
            }
        return CommandHideAdminResult.State(
            CommandHideBypassState(
                permission = permission,
                managedGrant = stored.managedGrant,
                unmanagedDirectGrant = stored.directGrant && !stored.managedMarker,
                effectiveBypass = player.hasPermission(permission),
            ),
        )
    }

    override fun setBypass(
        player: Player,
        enabled: Boolean,
        callback: (CommandHideAdminResult) -> Unit,
    ) {
        val current = status(player)
        if (current !is CommandHideAdminResult.State) {
            callback(current)
            return
        }

        val playerId = player.uniqueId
        if (!mutatingPlayers.add(playerId)) {
            callback(CommandHideAdminResult.Busy)
            return
        }

        val resolver = checkNotNull(policies)
        val permission = current.value.permission
        val store = checkNotNull(bypassStore)
        val scope = checkNotNull(tasks)
        val mutation =
            runCatching { store.setManagedGrant(playerId, permission, enabled) }
                .getOrElse { failure ->
                    mutatingPlayers.remove(playerId)
                    error(
                        "CommandHide bypass mutation could not start for player UUID {} and action {}",
                        playerId,
                        if (enabled) "allow" else "restrict",
                        failure,
                    )
                    callback(CommandHideAdminResult.Failed)
                    return
                }
        val tracked = InFlightCommandHideMutation(permission, enabled, mutation)
        inFlightMutations[playerId] = tracked
        mutation.whenComplete { outcome, failure ->
            val currentScope = tasks
            if (currentScope !== scope) {
                if (failure != null) {
                    error(
                        "CommandHide bypass mutation failed after a lifecycle transition for player UUID {} and action {}",
                        playerId,
                        if (tracked.enabled) "allow" else "restrict",
                        failure,
                    )
                }
                if (
                    currentScope != null &&
                    failure == null &&
                    (
                        outcome == CommandHideBypassMutation.APPLIED ||
                            outcome == CommandHideBypassMutation.UNCHANGED
                    )
                ) {
                    val scheduled = currentScope.runSync {
                        reconcileAfterLifecycleTransition(playerId, tracked)
                    }
                    if (scheduled == null) clearMutation(playerId, tracked)
                } else {
                    clearMutation(playerId, tracked)
                }
            }
        }
        mutation
            .whenCompleteSync(scope) { outcome, failure ->
                try {
                    if (failure != null) {
                        error(
                            "CommandHide bypass mutation failed for player UUID {} and action {}",
                            playerId,
                            if (enabled) "allow" else "restrict",
                            failure,
                        )
                        callback(CommandHideAdminResult.Failed)
                        return@whenCompleteSync
                    }

                    when (outcome) {
                        CommandHideBypassMutation.CONFLICTING_DENY -> {
                            callback(CommandHideAdminResult.ConflictingDeny)
                            return@whenCompleteSync
                        }

                        CommandHideBypassMutation.UNMANAGED_GRANT -> {
                            callback(CommandHideAdminResult.UnmanagedGrant)
                            return@whenCompleteSync
                        }

                        CommandHideBypassMutation.APPLIED,
                        CommandHideBypassMutation.UNCHANGED
                        -> Unit

                        null -> {
                            callback(CommandHideAdminResult.Failed)
                            return@whenCompleteSync
                        }
                    }

                    val currentPlayer = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline }
                    resolver.invalidate(playerId)
                    if (currentPlayer == null) {
                        callback(CommandHideAdminResult.TargetOffline)
                        return@whenCompleteSync
                    }
                    resolver.refresh(playerId, currentPlayer::hasPermission)
                    val treeUpdated = updateCommandTree(currentPlayer, playerId)
                    val updated = status(currentPlayer)
                    callback(
                        if (updated is CommandHideAdminResult.State) {
                            updated.copy(commandTreeUpdated = treeUpdated)
                        } else {
                            updated
                        },
                    )
                } finally {
                    clearMutation(playerId, tracked)
                }
            }
    }

    private fun reconcileAfterLifecycleTransition(
        playerId: UUID,
        tracked: InFlightCommandHideMutation,
    ) {
        try {
            val resolver = policies ?: return
            val currentPlayer = Bukkit.getPlayer(playerId)?.takeIf { it.isOnline }
            resolver.invalidate(playerId)
            if (currentPlayer != null) {
                resolver.refresh(playerId, currentPlayer::hasPermission)
                updateCommandTree(currentPlayer, playerId)
            }
            info(
                "CommandHide reconciled completed bypass mutation after lifecycle transition for player UUID {} and permission {}",
                playerId,
                tracked.permission,
            )
        } finally {
            clearMutation(playerId, tracked)
        }
    }

    private fun updateCommandTree(
        player: Player,
        playerId: UUID,
    ): Boolean =
        runCatching(player::updateCommands)
            .onFailure {
                warn(
                    "CommandHide bypass changed for player UUID {}, but command tree refresh failed: {}",
                    playerId,
                    it.message ?: it::class.java.simpleName,
                )
            }.isSuccess

    private fun clearMutation(
        playerId: UUID,
        tracked: InFlightCommandHideMutation,
    ) {
        if (inFlightMutations.remove(playerId, tracked)) {
            mutatingPlayers.remove(playerId)
        }
    }

    private fun logLoaded(
        snapshot: CommandHideSnapshot,
        action: String,
    ) {
        info(
            "CommandHide {}: enabled={}, groups={}, resolved-patterns={}",
            action,
            snapshot.enabled,
            snapshot.groupCount,
            snapshot.patternCount,
        )
    }
}

private object PlayerCommandTreeUpdater {
    fun update(player: org.bukkit.entity.Player) {
        player.updateCommands()
    }
}
