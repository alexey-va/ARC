package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.util.TextUtil

/**
 * /arc schedules — GUI управления расписанием команд.
 *
 * /arc schedules run <id> — немедленный запуск (console / arc.schedules.run).
 */
object SchedulesSubCommand : SubCommand {
    override val configKey = "schedules"
    override val defaultName = "schedules"
    override val defaultPermission = "arc.schedules.gui"
    override val defaultDescription = "GUI расписания консольных команд"
    override val defaultUsage = "/arc schedules [run <id>]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.isNotEmpty() && args[0].equals("run", ignoreCase = true)) {
            return handleRun(sender, args)
        }

        val player = sender.player
        if (player == null) {
            sender.sendMessage(CommandConfig.playerOnly())
            return true
        }

        ScheduledCommandsManager.openGui(player)
        return true
    }

    private fun handleRun(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (!sender.hasPermission("arc.schedules.run") && !sender.hasPermission("arc.admin")) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(CommandConfig.usage("/arc schedules run <id>"))
            return true
        }

        val id = args[1]
        val ok = ScheduledCommandsManager.runNow(id)
        if (ok) {
            sender.sendMessage(TextUtil.mm("<green>Запущена команда <white>$id", true))
        } else {
            sender.sendMessage(TextUtil.mm("<red>Команда <white>$id<red> не найдена", true))
        }
        return true
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? {
        if (args.size == 1) {
            return listOf("run").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("run", ignoreCase = true)) {
            return ScheduledCommandsManager.settings().entries().map { it.id }.filter {
                it.startsWith(args[1], ignoreCase = true)
            }
        }
        return emptyList()
    }
}
