package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.worldcontent.BreweryTableDialogs

/** Public entry point for the fixed Origin brewery table order dialog. */
object BrewerySubCommand : SubCommand {
    override val configKey = "brewery"
    override val defaultPermission: String? = null
    override val defaultDescription = "Выбрать блюдо у столика Луи"
    override val defaultUsage = "/arc brewery order"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        if (args.size != 1 || !args[0].equals("order", ignoreCase = true)) {
            sendUsage(player)
            return true
        }
        BreweryTableDialogs.openOrder(player)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        if (args.size == 1) listOf("order").tabComplete(args[0]) else emptyList()
}
