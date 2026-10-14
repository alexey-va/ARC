package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.integrations.origin.originService
import ru.arc.origin.api.ArcOriginCommands.CommandId
import ru.arc.util.TextUtil

object BrewerySubCommand : SubCommand {
    override val configKey = "brewery"
    override val defaultPermission: String? = null
    override val defaultDescription = "Выбрать блюдо у столика Луи"
    override val defaultUsage = "/arc brewery order [food|drinks|courtyard|restaurant]"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = originService() != null

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val service = originService()
        if (service == null) {
            sender.sendMessage(TextUtil.mm("<red>Сервисы Origin сейчас недоступны."))
            return true
        }
        return service.execute(CommandId.BREWERY, sender, args)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        originService()?.tabComplete(CommandId.BREWERY, sender, args)
}
