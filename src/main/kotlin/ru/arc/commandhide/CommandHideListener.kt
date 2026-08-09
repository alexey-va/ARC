package ru.arc.commandhide

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.TabCompleteEvent

class CommandHideListener internal constructor(
    private val policies: CommandHidePolicyResolver,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val policy = policy(event.player)
        if (!policy.blocks(event.message)) return

        event.isCancelled = true
        policy.blockedMessage?.let(event.player::sendMessage)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        val player = event.sender as? Player ?: return
        if (!event.isCommand) return

        val filtered = policy(player).filterCompletions(event.buffer, event.completions)
        if (filtered.size != event.completions.size) {
            event.completions = filtered
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerCommandSend(event: PlayerCommandSendEvent) {
        val policy = refresh(event.player)
        if (policy.isEmpty) return
        event.commands.removeIf(policy::hidesRoot)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBrigadierCommandTree(event: AsyncPlayerSendCommandsEvent<*>) {
        val policy =
            if (event.isAsynchronous) {
                policies.cached(event.player.uniqueId) ?: return
            } else {
                refresh(event.player)
            }
        CommandTreePruner.prune(event.commandNode, policy)
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
