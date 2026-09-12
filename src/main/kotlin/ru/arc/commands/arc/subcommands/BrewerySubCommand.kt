package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.commands.arc.tabComplete
import ru.arc.worldcontent.BreweryTableDialogs

/** Public entry point for seated dining dialogs in Origin. */
object BrewerySubCommand : SubCommand {
    override val configKey = "brewery"
    override val defaultPermission: String? = null
    override val defaultDescription = "Выбрать блюдо у столика Луи"
    override val defaultUsage = "/arc brewery order [food|drinks|courtyard|restaurant]"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        if (args.isEmpty() || args.size > 2 || !args[0].equals("order", ignoreCase = true)) {
            sendUsage(player)
            return true
        }
        val menu = if (args.size == 1) BreweryTableDialogs.Menu.FOOD else BreweryTableDialogs.Menu.parse(args[1])
        if (menu == null) {
            sendUsage(player)
            return true
        }
        BreweryTableDialogs.openOrder(player, menu)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("order").tabComplete(args[0])
            2 -> BreweryTableDialogs.Menu.entries.map { it.id }.tabComplete(args[1])
            else -> emptyList()
        }
}
