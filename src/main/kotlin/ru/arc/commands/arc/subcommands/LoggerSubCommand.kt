package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.util.Logging

/**
 * /arc logger - управление уровнем логирования.
 *
 * Уровни: DEBUG, INFO, WARN, ERROR
 */
object LoggerSubCommand : SubCommand {

    override val configKey = "logger"
    override val defaultName = "logger"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Установить уровень логирования"
    override val defaultUsage = "/arc logger <DEBUG|INFO|WARN|ERROR>"

    private val levels = Logging.Level.entries.map { it.name }

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val currentLevel = Logging.getLogLevel()

        // /arc logger - показать текущий уровень
        if (args.isEmpty()) {
            sender.sendMessage(
                CommandConfig.get(
                    "logger.current-level",
                    "<gray>Текущий уровень логов: <white>%level%",
                    "%level%", currentLevel.name
                )
            )
            sender.sendMessage(
                CommandConfig.get(
                    "logger.change-hint",
                    "<gray>Изменить: <white>/arc logger <уровень>"
                )
            )
            sender.sendMessage(CommandConfig.loggerAvailableLevels(levels.joinToString(", ")))
            return true
        }

        val levelName = args[0].uppercase()

        val level = try {
            Logging.Level.valueOf(levelName)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage(CommandConfig.loggerInvalidLevel(levelName))
            sender.sendMessage(CommandConfig.loggerAvailableLevels(levels.joinToString(", ")))
            return true
        }

        if (level == currentLevel) {
            sender.sendMessage(
                CommandConfig.get(
                    "logger.already-set",
                    "<gray>Уровень логов уже установлен на <white>%level%",
                    "%level%", level.name
                )
            )
            return true
        }

        Logging.setLogLevel(level)
        sender.sendMessage(
            CommandConfig.get(
                "logger.level-changed",
                "<gray>Уровень логов изменён: <white>%old% <gray>→ <white>%new%",
                "%old%", currentLevel.name,
                "%new%", level.name
            )
        )

        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> levels.tabComplete(args[0].uppercase())
            else -> null
        }
    }
}
