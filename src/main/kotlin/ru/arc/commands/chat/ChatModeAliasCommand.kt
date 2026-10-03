package ru.arc.commands.chat

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.chat.ChatMode
import ru.arc.commands.arc.subcommands.ChatSubCommand

object ChatModeAliasCommand : CommandExecutor {
    internal fun register(plugin: JavaPlugin) {
        val commandMap = plugin.server.commandMap
        for (name in listOf("g", "l")) {
            val command = plugin.getCommand(name) ?: continue
            command.setExecutor(this)
            // justTeams registers /g as a primary command before ARC loads.
            command.unregister(commandMap)
            commandMap.knownCommands.remove(name)
            commandMap.register(name, plugin.name.lowercase(), command)
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        if (args.isNotEmpty()) return false
        val mode =
            when (label.substringAfter(':').lowercase()) {
                "g" -> ChatMode.GLOBAL
                "l" -> ChatMode.LOCAL
                else -> return false
            }
        return ChatSubCommand.selectMode(sender, mode)
    }
}
