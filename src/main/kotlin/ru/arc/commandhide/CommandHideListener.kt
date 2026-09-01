package ru.arc.commandhide

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.TabCompleteEvent

class CommandHideListener internal constructor(
    private val policies: CommandHidePolicyResolver,
    private val runNextTick: (Runnable) -> Unit,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val policy = policy(event.player)
        if (!policy.blocks(event.message)) return

        event.isCancelled = true
        policy.blockedMessage?.let(event.player::sendMessage)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        val player = event.sender as? Player ?: return
        if (!event.isCommand) return

        val filtered = policy(player).filterCompletions(event.buffer, event.completions)
        if (filtered.size != event.completions.size) {
            event.completions = filtered
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerCommandSend(event: PlayerCommandSendEvent) {
        val policy = refresh(event.player)
        if (policy.isEmpty) return
        event.commands.removeIf(policy::hidesRoot)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBrigadierCommandTree(event: AsyncPlayerSendCommandsEvent<*>) {
        val policy =
            if (event.isAsynchronous) {
                // Bukkit permission checks are not async-safe. A cached immutable policy
                // lets repeat command-tree refreshes be pruned on Paper's first pass.
                policies.cached(event.player.uniqueId) ?: return
            } else {
                // Paper always follows its async pass with this synchronous pass on the
                // same mutable tree, so a cold cache is still handled before serialization.
                refresh(event.player)
            }
        CommandTreePruner.prune(event.commandNode, policy)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        runNextTick(
            Runnable {
                if (!player.isOnline) return@Runnable
                // The initial command tree can be built before permission plugins finish
                // their join lifecycle. Rebuild once afterwards with a fresh policy.
                policies.invalidate(player.uniqueId)
                player.updateCommands()
            },
        )
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        policies.invalidate(event.player.uniqueId)
    }

    private fun policy(player: Player): CommandHidePolicy =
        policies.policy(player.uniqueId, player::hasPermission)

    private fun refresh(player: Player): CommandHidePolicy =
        policies.refresh(player.uniqueId, player::hasPermission)
}
