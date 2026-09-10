package ru.arc.eliteloot

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Opens the network-wide lost-loot mailbox on any backend where ARC runs. */
object LostLootCommand : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty()) return false
        val player = sender as? Player ?: run {
            sender.sendMessage("Эта команда доступна только игроку.")
            return true
        }
        LostLootModule.open(player)
        return true
    }
}
