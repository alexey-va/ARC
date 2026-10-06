package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.util.TextUtil
import java.util.Locale

object ServerSubCommand : SubCommand {
    override val configKey = "server"
    override val defaultName = "server"
    override val defaultDescription = "Перейти на основной сервер или на сервер ETD"
    override val defaultUsage = "/arc server <main|etd> [player]"

    private val handler = ServerCommandHandler(
        findPlayer = { name -> Bukkit.getPlayerExact(name) },
        transfer = { player, server -> ARC.pluginMessenger?.sendPlayerToServer(player, server) == true },
        usage = { sender -> sendUsage(sender) },
        playerRequired = { it.sendMessage(TextUtil.mm("<red>Эту команду можно выполнить только для игрока.")) },
        playerNotFound = { sender, name -> sender.sendMessage(TextUtil.mm("<red>Игрок <white>$name<red> не найден.")) },
        transferUnavailable = { sender -> sender.sendMessage(TextUtil.mm("<red>Сетевой переход временно недоступен.")) },
    )

    override fun execute(sender: CommandSender, args: Array<String>): Boolean = handler.execute(sender, args)

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("main", "etd").tabComplete(args[0])
            2 -> if (sender.hasPermission(SWITCH_OTHER_PERMISSION)) tabCompletePlayers(args[1]) else null
            else -> null
        }

    private const val SWITCH_OTHER_PERMISSION = "arc.switch-other"
}

internal class ServerCommandHandler(
    private val findPlayer: (String) -> Player?,
    private val transfer: (Player, String) -> Boolean,
    private val usage: (CommandSender) -> Unit,
    private val playerRequired: (CommandSender) -> Unit,
    private val playerNotFound: (CommandSender, String) -> Unit,
    private val transferUnavailable: (CommandSender) -> Unit,
    private val hasSwitchOtherPermission: (CommandSender) -> Boolean = { it.hasPermission("arc.switch-other") },
) {
    fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size !in 1..2) {
            usage(sender)
            return true
        }

        val targetServer = when (args[0].lowercase(Locale.ROOT)) {
            "main" -> "spawn"
            "etd" -> "etd"
            else -> {
                usage(sender)
                return true
            }
        }

        val target =
            if (args.size == 2 && hasSwitchOtherPermission(sender)) {
                findPlayer(args[1]).also { if (it == null) playerNotFound(sender, args[1]) }
                    ?: return true
            } else {
                (sender as? Player).also { if (it == null) playerRequired(sender) }
                    ?: return true
            }

        if (!transfer(target, targetServer)) transferUnavailable(sender)
        return true
    }
}
