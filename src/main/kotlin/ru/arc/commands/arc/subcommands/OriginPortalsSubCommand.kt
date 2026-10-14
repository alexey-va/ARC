package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.integrations.origin.originService
import ru.arc.origin.api.ArcOriginCommands.CommandId
import ru.arc.util.TextUtil

object OriginPortalsSubCommand : SubCommand {
    override val configKey = "originportals"
    override val defaultName = "originportals"
    override val defaultPermission: String? = null
    override val defaultDescription = "Войти в портал Slimefun или настроить Origin-порталы"
    override val defaultUsage = "/arc originportals <enter slimefun|move <survival|mining|vanilla|gallery_exit|slimefun|furniture_entry>>"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = originService() != null

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val service = originService()
        if (service == null) {
            sender.sendMessage(TextUtil.mm("<red>Сервисы Origin сейчас недоступны."))
            return true
        }
        return service.execute(CommandId.ORIGIN_PORTALS, sender, args)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        originService()?.tabComplete(CommandId.ORIGIN_PORTALS, sender, args)

    fun isPublicAction(args: Array<String>): Boolean =
        originService()?.isPublicAction(CommandId.ORIGIN_PORTALS, args) == true
}
