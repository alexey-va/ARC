package ru.arc.commands.chat

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import ru.arc.chat.ChatMode
import ru.arc.commands.arc.subcommands.ChatSubCommand

object ChatModeAliasCommand : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        if (args.isNotEmpty()) return false
        val mode =
            when (label.lowercase()) {
                "g" -> ChatMode.GLOBAL
                "l" -> ChatMode.LOCAL
                else -> return false
            }
        return ChatSubCommand.selectMode(sender, mode)
    }
}
