package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

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
        val plugin = ARC.instance
        val nativeWork = plugin.nativeWork
        val dispatched = runCatching { ScheduledCommandsManager.runNowAsync(id) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        dispatched.whenComplete { _, failure ->
            if (failure != null) plugin.logger.log(Level.WARNING, "Scheduled command run failed for $id", failure)
        }
        plugin.whenCompleteOnMain(dispatched) { ok, failure ->
            if (ARC.plugin !== plugin || !plugin.isEnabled || plugin.nativeWork !== nativeWork ||
                sender is Player && !sender.isOnline) return@whenCompleteOnMain
            val message = when {
                failure != null -> "<red>Не удалось выполнить"
                ok == true -> "<green>Запущена команда <white>$id"
                else -> "<red>Команда <white>$id<red> не найдена"
            }
            sender.sendMessage(TextUtil.mm(message, true))
        }
        return true
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? {
        if (!sender.hasPermission("arc.schedules.run") && !sender.hasPermission("arc.admin")) return emptyList()
        if (args.size == 1) {
            return listOf("run").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("run", ignoreCase = true)) {
            return ScheduledCommandsManager.commandIds(args[1])
        }
        return emptyList()
    }
}
