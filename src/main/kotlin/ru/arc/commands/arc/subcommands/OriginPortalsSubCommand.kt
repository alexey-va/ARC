package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.origin.OriginPortalId
import ru.arc.origin.OriginPortalEnterResult
import ru.arc.origin.OriginPortalsModule
import ru.arc.util.TextUtil
import java.util.Locale

/** `/arc originportals move <portal>` is administrative; the explicit enter action is public. */
object OriginPortalsSubCommand : SubCommand {
    override val configKey = "originportals"
    override val defaultName = "originportals"
    override val defaultPermission: String? = null
    override val defaultDescription = "Войти в портал Slimefun или настроить Origin-порталы"
    override val defaultUsage = "/arc originportals <enter slimefun|move <survival|mining|vanilla|gallery_exit|slimefun>>"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(TextUtil.mm("<red>Эта команда доступна только игрокам.", true))
            return true
        }
        if (args.size == 2 && args[0].equals("enter", ignoreCase = true)) {
            val id = OriginPortalId.parse(args[1])
            if (id != OriginPortalId.SLIMEFUN) {
                sender.sendMessage(TextUtil.mm("<red>Доступен только вход в портал Slimefun."))
                return true
            }
            val result = OriginPortalsModule.enter(id, player)
            when (result) {
                OriginPortalEnterResult.REQUESTED -> Unit
                OriginPortalEnterResult.PORTAL_DISABLED -> sender.sendMessage(
                    TextUtil.mm("<red>Портал Slimefun пока закрыт."),
                )
                OriginPortalEnterResult.WRONG_WORLD -> sender.sendMessage(
                    TextUtil.mm("<red>Войти в Slimefun можно только из зала Origin."),
                )
                OriginPortalEnterResult.TOO_FAR -> sender.sendMessage(
                    TextUtil.mm("<red>Подойдите ближе к порталу Slimefun."),
                )
                OriginPortalEnterResult.ALREADY_PENDING -> sender.sendMessage(
                    TextUtil.mm("<yellow>Запрос перехода уже выполняется."),
                )
                OriginPortalEnterResult.COOLDOWN -> sender.sendMessage(
                    TextUtil.mm("<yellow>Подождите немного перед повторным запросом перехода."),
                )
                OriginPortalEnterResult.TRANSFER_UNAVAILABLE -> sender.sendMessage(
                    TextUtil.mm("<red>Не удалось отправить запрос на переход. Попробуйте позже."),
                )
                OriginPortalEnterResult.UNSUPPORTED_DESTINATION -> sender.sendMessage(
                    TextUtil.mm("<red>Этот портал не поддерживает переход на другой сервер."),
                )
            }
            return true
        }
        if (args.size != 2 || !args[0].equals("move", ignoreCase = true)) {
            sendUsage(sender)
            return true
        }
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(TextUtil.mm("<red>Недостаточно прав для перемещения портала."))
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
            1 -> listOf("enter", "move").tabComplete(args[0])
            2 -> when {
                args[0].equals("move", ignoreCase = true) -> OriginPortalId.entries.map { it.key }.tabComplete(args[1])
                args[0].equals("enter", ignoreCase = true) -> listOf(OriginPortalId.SLIMEFUN.key).tabComplete(args[1])
                else -> null
            }
            else -> null
        }

    fun isPublicAction(args: Array<String>): Boolean =
        args.size == 2 && args[0].equals("enter", ignoreCase = true) &&
            args[1].equals(OriginPortalId.SLIMEFUN.key, ignoreCase = true)

    private const val ADMIN_PERMISSION = "arc.origin.portals.admin"
}
