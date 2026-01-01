package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.misc.JoinMessageGui
import ru.arc.util.GuiUtils

/**
 * /arc joinmessage - Open join message customization GUI.
 */
object JoinMessageSubCommand : SubCommand {

    override val configKey = "joinmessage"
    override val defaultName = "joinmessage"
    override val defaultPermission = "arc.join-message-gui"
    override val defaultDescription = "Настроить сообщение при входе"
    override val defaultUsage = "/arc joinmessage"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        GuiUtils.constructAndShowAsync({ JoinMessageGui(player, true, 0) }, player)
        return true
    }
}
