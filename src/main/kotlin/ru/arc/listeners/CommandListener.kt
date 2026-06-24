package ru.arc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import ru.arc.ARC
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.audit.AuditManager
import ru.arc.audit.Type
import ru.arc.config.ConfigManager
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import ru.arc.xserver.playerlist.PlayerManager

class CommandListener : Listener {

    private val commandConfig = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerPlaceBlock(ev: BlockPlaceEvent) {
        if (Portal.isOccupied(ev.blockPlaced)) ev.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommand(ev: PlayerCommandPreprocessEvent) {
        val args = ev.message.split(" ")
        warpCommand(ev, args)
        moneyCommand(ev.player, ev, args)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServerCommand(serverCommandEvent: ServerCommandEvent) {
        val args = serverCommandEvent.command.split(" ")
        moneyCommandServer(serverCommandEvent, args)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onTabComplete(tabCompleteEvent: TabCompleteEvent) {
        moneyTabComplete(tabCompleteEvent)
    }

    private fun moneyTabComplete(event: TabCompleteEvent) {
        if (!event.buffer.startsWith("/money ")) return
        val args = event.buffer.split(" ")
        var len = args.size
        if (event.buffer.endsWith(" ")) len++
        when (len) {
            2 -> event.completions = mutableListOf("give", "set", "take")
            3 -> event.completions = PlayerManager.getPlayerNames().toMutableList()
            4 -> event.completions = mutableListOf("100")
        }
    }

    private fun moneyCommand(player: Player, ev: Cancellable, args: List<String>) {
        val sub = setOf("give", "set", "take")
        if (args.size > 2 && args[0].equals("/money", ignoreCase = true) && sub.contains(args[1])) {
            if (!player.hasPermission("rediseconomy.admin")) {
                player.sendMessage(TextUtil.noPermissions())
                return
            }
            if (args.size == 4) {
                ev.isCancelled = true
                try {
                    val money = args[3].toDouble()
                    val command = "money ${args[2]} vault ${args[1]} $money"
                    player.performCommand(command)
                    AuditManager.operation(args[2], money, Type.COMMAND, player.name)
                    info("Rerouted {} to {}", args.joinToString(" "), command)
                } catch (e: Exception) {
                    error("Failed to reroute /money give command to /money <player> vault give <amount>", e)
                }
            } else {
                player.sendMessage(mm("<red>Правильное использование: <gray>/money give/set/take <игрок> <сумма>"))
            }
        }
    }

    private fun moneyCommandServer(ev: Cancellable, args: List<String>) {
        val sub = setOf("give", "set", "take")
        if (args.size > 2 && args[0].equals("money", ignoreCase = true) && sub.contains(args[1])) {
            if (args.size == 4) {
                ev.isCancelled = true
                try {
                    val money = args[3].toDouble()
                    val command = "money ${args[2]} vault ${args[1]} $money"
                    ARC.trySeverCommand(command)
                    AuditManager.operation(args[2], money, Type.COMMAND, "Server")
                    info("Rerouted {} to {}", args.joinToString(" "), command)
                } catch (e: Exception) {
                    error("Failed to reroute /money give command to /money <player> vault give <amount>", e)
                }
            }
        }
    }

    private fun warpCommand(ev: PlayerCommandPreprocessEvent, args: List<String>) {
        if (ev.player.hasPermission("arc.bypass-portal")) return
        if (!commandConfig.bool("portal.command-portals", true)) return
        if (args.size < 2) return

        val excludedSubCommands = commandConfig.stringList("portal.excluded-sub-commands").toHashSet()
        val aliases = commandConfig.stringList("portal.aliases").toHashSet()
        val mainCommand = args[0].substring(1)
        val isCmiWarp = "/cmi" == args[0] && "warp" == args[1]

        if (!aliases.contains(mainCommand) && !isCmiWarp) return
        if (excludedSubCommands.contains(args[1])) return

        var warpExists = false
        val ifCheck = commandConfig.bool("portal.check-player-warps", true) ||
            commandConfig.bool("portal.check-cmi-warps", true)
        if (commandConfig.bool("portal.check-player-warps", true) && HookRegistry.playerWarpsHook != null) {
            warpExists = warpExists || HookRegistry.playerWarpsHook!!.warpExists(args[1], ev.player)
        }
        if (commandConfig.bool("portal.check-cmi-warps", true) && HookRegistry.cmiHook != null) {
            warpExists = warpExists || HookRegistry.cmiHook!!.warpExists(args[1])
        }
        if (ifCheck && !warpExists) return
        Portal(ev.player.uniqueId, PortalData(PortalData.ActionType.COMMAND, null, null, ev.message.substring(1)))
        ev.isCancelled = true
    }
}
