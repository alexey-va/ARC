package ru.arc.commands.arc

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * Bridges legacy top-level commands from plugin.yml (e.g. /sound-follow)
 * to existing [SubCommand] implementations used by /arc.
 */
class LegacySubCommandExecutor(
    private val subCommand: SubCommand,
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        if (!sender.checkPermission(subCommand.permission)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }

        if (subCommand.playerOnly && sender.player == null) {
            sender.sendMessage(CommandConfig.playerOnly())
            return true
        }

        return subCommand.execute(sender, args)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): List<String>? {
        if (!sender.checkPermission(subCommand.permission)) {
            return null
        }
        return subCommand.tabComplete(sender, args)
    }
}
