package ru.arc.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.helpcenter.HelpCenterModule

object MainMenuCommand : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty()) return false
        val player = sender as? Player ?: run {
            sender.sendMessage("Эта команда доступна только игроку.")
            return true
        }
        return HelpCenterModule.open(player)
    }
}
