package ru.arc.playertransfer

import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.RemoteConsoleCommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete

internal object PlayerTransferSubCommand : SubCommand {
    override val configKey = "playertransfer"
    override val defaultPermission = "arc.playertransfer.admin"
    override val defaultDescription = "Проверить данные двух аккаунтов перед переносом."
    override val defaultUsage = "/arc playertransfer preview <старый-uuid> <новый-uuid>"

    override fun isAvailable(): Boolean = PlayerTransferModule.ready

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (!isLocalConsole(sender)) {
            sender.sendMessage("Перенос игроков доступен только из локальной консоли.")
            return true
        }
        if (args.size != 3 || !args[0].equals("preview", ignoreCase = true)) {
            sendUsage(sender)
            return true
        }
        val pair = try {
            PlayerTransferPair.parse(args[1], args[2])
        } catch (failure: IllegalArgumentException) {
            sender.sendMessage(failure.message ?: "Не удалось прочитать UUID.")
            return true
        }
        PlayerTransferModule.preview(sender, pair)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (args.size == 1 && isLocalConsole(sender)) listOf("preview").tabComplete(args[0]) else emptyList()

    private fun isLocalConsole(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender && sender !is RemoteConsoleCommandSender
}
