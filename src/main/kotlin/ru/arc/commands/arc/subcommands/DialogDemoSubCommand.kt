package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.dialogdemo.DialogDemoModule

object DialogDemoSubCommand : SubCommand {
    override val configKey = "dialogdemo"
    override val defaultDescription = "Лаборатория возможностей нативных диалогов"
    override val defaultPlayerOnly = true
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        DialogDemoModule.open(player, args.firstOrNull() ?: "root", args.drop(1).take(3).joinToString(" "))
        return true
    }
    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (args.size == 1) DialogDemoModule.pages.filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()
}
