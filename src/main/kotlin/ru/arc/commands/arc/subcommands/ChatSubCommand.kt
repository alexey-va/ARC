package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.chat.ChatMode
import ru.arc.chat.ChatModeSelection
import ru.arc.chat.ChatModeService
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.core.sync
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.warn

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
        debug("[ChatMode] command player={} requested={}", player.name, mode)
        ChatModeService.selectMode(player.uniqueId, mode).whenComplete { selection, failure ->
            sync {
                if (!player.isOnline) return@sync
                if (failure != null) {
                    warn("[ChatMode] command failed player={} requested={}", player.name, mode, failure)
                    player.sendMessage(
                        CommandConfig.get(
                            "chat.error",
                            "<red>Не удалось переключить режим чата. Попробуйте ещё раз.",
                        ),
                    )
                    return@sync
                }
                debug("[ChatMode] command player={} requested={} result={}", player.name, mode, selection)
                player.sendMessage(message(mode, selection))
            }
        }
        return true
    }

    private fun message(
        mode: ChatMode,
        selection: ChatModeSelection,
    ) =
        when (selection) {
            ChatModeSelection.CHANGED ->
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
                }

            ChatModeSelection.ALREADY_SELECTED ->
                when (mode) {
                    ChatMode.LOCAL ->
                        CommandConfig.get(
                            "chat.local-already",
                            "<gray>Вы уже в <white>локальном<gray> режиме чата. Переключить: <white>/g",
                        )

                    ChatMode.GLOBAL ->
                        CommandConfig.get(
                            "chat.global-already",
                            "<gray>Вы уже в <white>глобальном<gray> режиме чата. Переключить: <white>/l",
                        )
                }
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
