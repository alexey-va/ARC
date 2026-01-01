package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.misc.JoinMessageGui
import ru.arc.util.GuiUtils

/**
 * /arc quitmessage - Open quit message customization GUI.
 */
object QuitMessageSubCommand : SubCommand {
    
    override val configKey = "quitmessage"
    override val defaultName = "quitmessage"
    override val defaultPermission = "arc.join-message-gui"
    override val defaultDescription = "Настроить сообщение при выходе"
    override val defaultUsage = "/arc quitmessage"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        GuiUtils.constructAndShowAsync({ JoinMessageGui(player, false, 0) }, player)
        return true
    }
}
