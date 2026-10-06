package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.decorinteraction.DecorInteractionAction
import ru.arc.decorinteraction.DecorInteractionModule
import java.util.Locale

/** Canonical ARC command route for the legacy Survival decor interactions. */
object DecorInteractionSubCommand : SubCommand {
    override val configKey = "interact"
    override val defaultName = "interact"
    override val defaultPermission = DecorInteractionModule.PERMISSION
    override val defaultDescription = "Использовать интеракцию декора"
    override val defaultUsage = "/arc interact <drink|fountain|well|milk|tea|fish|harvest|rest> <игрок> [beer|wine|corn|rice]"

    override fun isAvailable(): Boolean = DecorInteractionModule.available

    override fun execute(sender: CommandSender, args: Array<String>): Boolean =
        DecorInteractionModule.execute(sender, args)

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        when (args.size) {
            1 -> DecorInteractionAction.entries.map { it.command }.tabComplete(args[0])
            2 -> tabCompletePlayers(args[1])
            3 ->
                when (args[0].lowercase(Locale.ROOT)) {
                    "drink" -> listOf("beer", "wine").tabComplete(args[2])
                    "harvest" -> listOf("corn", "rice").tabComplete(args[2])
                    else -> emptyList()
                }
            else -> emptyList()
        }

}
