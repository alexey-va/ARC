package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.origin.OriginPortalId
import ru.arc.origin.OriginPortalsModule
import ru.arc.util.TextUtil
import java.util.Locale

/** `/arc originportals move <portal>` persists the invoking player's feet. */
object OriginPortalsSubCommand : SubCommand {
    override val configKey = "originportals"
    override val defaultName = "originportals"
    override val defaultPermission = "arc.origin.portals.admin"
    override val defaultDescription = "Переместить центральный Origin-портал"
    override val defaultUsage = "/arc originportals move <survival|mining|vanilla|gallery_exit>"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(TextUtil.mm("<red>Эта команда доступна только игрокам.", true))
            return true
        }
        if (args.size != 2 || !args[0].equals("move", ignoreCase = true)) {
            sendUsage(sender)
            return true
        }
        val id = OriginPortalId.parse(args[1]) ?: run {
            sender.sendMessage(TextUtil.mm("<red>Неизвестный портал: <white>${args[1]}", true))
            sendUsage(sender)
            return true
        }
        if (!OriginPortalsModule.move(id, player)) {
            sender.sendMessage(TextUtil.mm("<red>Не удалось сохранить координаты портала.", true))
            return true
        }
        sender.sendMessage(
            TextUtil.mm(
                "<green>Портал <white>${id.key}<green> перемещён на <white>%.3f %.3f %.3f <green>(yaw %.1f).".format(Locale.ROOT,
                    player.location.x,
                    player.location.y,
                    player.location.z,
                    player.location.yaw,
                ),
                true,
            ),
        )
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("move").tabComplete(args[0])
            2 -> if (args[0].equals("move", ignoreCase = true)) OriginPortalId.entries.map { it.key }.tabComplete(args[1]) else null
            else -> null
        }
}
