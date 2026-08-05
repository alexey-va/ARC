package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatModeService
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete

object ChatSubCommand : SubCommand {
    override val configKey = "chat"
    override val defaultDescription = "Переключить локальный или глобальный режим чата"
    override val defaultUsage = "/arc chat <local|global>"
    override val defaultPlayerOnly = true

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val mode =
            args.singleOrNull()?.let {
                when (it.lowercase()) {
                    "local" -> ChatMode.LOCAL
                    "global" -> ChatMode.GLOBAL
                    else -> null
                }
            }
        if (mode == null) {
            sendUsage(sender)
            return true
        }
        return selectMode(sender, mode)
    }

    internal fun selectMode(
        sender: CommandSender,
        mode: ChatMode,
    ): Boolean {
        val player = requirePlayer(sender) ?: return true
        ChatModeService.setMode(player.uniqueId, mode)
        player.sendMessage(
            when (mode) {
                ChatMode.LOCAL ->
                    CommandConfig.get(
                        "chat.local",
                        "<gray>Режим чата: <white>локальный<gray>. Переключить: <white>/g",
                    )
                ChatMode.GLOBAL ->
                    CommandConfig.get(
                        "chat.global",
                        "<gray>Режим чата: <white>глобальный<gray>. Переключить: <white>/l",
                    )
            },
        )
        return true
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> listOf("local", "global").tabComplete(args[0])
            else -> null
        }
}
