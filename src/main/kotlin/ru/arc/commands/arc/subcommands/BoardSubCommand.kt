package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.board.guis.BoardGui
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.util.GuiUtils

/**
 * /arc board - Opens the scoreboard GUI.
 */
object BoardSubCommand : SubCommand {

    override val configKey = "board"
    override val defaultName = "board"
    override val defaultPermission = "arc.board"
    override val defaultDescription = "Открыть настройки скорборда"
    override val defaultUsage = "/arc board"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        GuiUtils.constructAndShowAsync({ BoardGui(player) }, player)
        return true
    }
}
