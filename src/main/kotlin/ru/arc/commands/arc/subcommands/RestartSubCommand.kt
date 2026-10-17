package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.restart.CancelResult
import ru.arc.restart.RestartDurationFormat
import ru.arc.restart.RestartFlagParser
import ru.arc.restart.RestartManager
import ru.arc.restart.RestartServerNames
import ru.arc.restart.RestartServerTarget
import ru.arc.restart.ScheduleResult
import ru.arc.util.TextUtil

/**
 * /arc restart [-servers all|spawn,survival] [-delay 3m]
 * /arc restart cancel [-servers all|...]
 */
object RestartSubCommand : SubCommand {
    override val configKey = "restart"
    override val defaultName = "restart"
    override val defaultPermission = "arc.restart"
    override val defaultDescription = "Плановая перезагрузка сервера с предупреждением игрокам"
    override val defaultUsage = "/arc restart [-servers all|spawn,survival] [-delay 3m] | /arc restart cancel"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val config = RestartManager.settings()
        if (!config.enabled) {
            sender.sendMessage(TextUtil.mm("<red>Модуль перезагрузки отключён", true))
            return true
        }

        val flags = RestartFlagParser.parse(args, config.defaultDelay)

        if (flags.cancel) {
            return handleCancel(sender, flags)
        }

        if (!canSchedule(sender, flags.serverTarget)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }

        val result = RestartManager.scheduleFromCommand(flags, sender.name)
        when (result) {
            is ScheduleResult.Scheduled -> {
                val delayText = RestartDurationFormat.format(result.delay)
                sender.sendMessage(TextUtil.mm(config.messageScheduled.replace("<delay>", delayText), true))
            }

            is ScheduleResult.Published -> {
                val delayText = RestartDurationFormat.format(result.delay)
                val targetText = formatTarget(result.target)
                sender.sendMessage(
                    TextUtil.mm(
                        "<green>Команда перезагрузки отправлена (<white>$targetText<green>) через <white>$delayText",
                        true,
                    ),
                )
            }

            ScheduleResult.AlreadyPending -> {
                sender.sendMessage(TextUtil.mm(config.messageAlreadyPending, true))
            }

            ScheduleResult.Disabled -> {
                sender.sendMessage(TextUtil.mm("<red>Модуль перезагрузки отключён", true))
            }

            ScheduleResult.Failed -> {
                sender.sendMessage(TextUtil.mm("<red>Не удалось запланировать перезагрузку", true))
            }
        }
        return true
    }

    private fun handleCancel(
        sender: CommandSender,
        flags: ru.arc.restart.RestartFlags,
    ): Boolean {
        val config = RestartManager.settings()
        if (!canCancel(sender, flags.serverTarget)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }

        when (val result = RestartManager.cancelFromCommand(flags, sender.name)) {
            CancelResult.Cancelled -> {
                sender.sendMessage(TextUtil.mm(config.messageCancelled, true))
            }

            is CancelResult.Published -> {
                val targetText = formatTarget(result.target)
                sender.sendMessage(
                    TextUtil.mm(
                        "<yellow>Отмена перезагрузки отправлена (<white>$targetText<yellow>)",
                        true,
                    ),
                )
            }

            CancelResult.NothingPending -> {
                sender.sendMessage(TextUtil.mm(config.messageNothingPending, true))
            }
        }
        return true
    }

    private fun canSchedule(
        sender: CommandSender,
        target: RestartServerTarget,
    ): Boolean {
        val config = RestartManager.settings()
        if (sender.hasPermission("arc.admin")) return true
        if (!sender.hasPermission(config.permissionRestart)) return false
        if (target == RestartServerTarget.All || target is RestartServerTarget.Named) {
            return sender.hasPermission(config.permissionAllServers)
        }
        return true
    }

    private fun canCancel(
        sender: CommandSender,
        target: RestartServerTarget,
    ): Boolean {
        val config = RestartManager.settings()
        if (sender.hasPermission("arc.admin")) return true
        if (!sender.hasPermission(config.permissionCancel) && !sender.hasPermission(config.permissionRestart)) {
            return false
        }
        if (target == RestartServerTarget.All || target is RestartServerTarget.Named) {
            return sender.hasPermission(config.permissionAllServers)
        }
        return true
    }

    private fun formatTarget(target: RestartServerTarget): String =
        when (target) {
            RestartServerTarget.All -> "all"
            RestartServerTarget.Current -> ARC.serverName ?: "current"
            is RestartServerTarget.Named -> target.servers.joinToString(",")
        }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String> {
        if (args.isEmpty()) return emptyList()
        val cancel = args.size > 1 && args[0].equals("cancel", ignoreCase = true)
        val allowed = if (cancel) canCancel(sender, RestartServerTarget.Current) else canSchedule(sender, RestartServerTarget.Current)
        val remote = if (cancel) canCancel(sender, RestartServerTarget.All) else canSchedule(sender, RestartServerTarget.All)
        val flags = if (allowed) tabCompleteFlags(if (cancel) args.copyOfRange(1, args.size) else args, remote, !cancel) else emptyList()
        return if (args.size == 1 && canCancel(sender, RestartServerTarget.Current)) {
            (listOf("cancel") + flags).tabComplete(args[0])
        } else flags
    }

    private fun tabCompleteFlags(args: Array<String>, allowServers: Boolean, allowDelay: Boolean): List<String> {
        val last = args.lastOrNull().orEmpty()
        val prev = args.getOrNull(args.size - 2)
        val parsed = RestartFlagParser.parseFlags(args.dropLast(1).toTypedArray())
        val hasServers = parsed.containsKey("servers")
        val hasDelay = parsed.containsKey("delay")

        val nextFlags =
            buildList {
                if (allowServers && !hasServers) {
                    add("-servers")
                    add("-servers:all")
                }
                if (allowDelay && !hasDelay) {
                    add("-delay")
                    add("-delay:3m")
                }
            }

        if (last.startsWith("-servers:", ignoreCase = true)) {
            return if (allowServers && !hasServers) RestartServerNames.tabComplete(RestartManager.settings(), last.substringAfter(':'))
                .map { "-servers:$it" } else emptyList()
        }

        if (prev.equals("-servers", ignoreCase = true) && !last.startsWith("-")) {
            return if (allowServers && !hasServers) RestartServerNames.tabComplete(RestartManager.settings(), last) else emptyList()
        }

        if (last.startsWith("-delay:", ignoreCase = true)) {
            return if (allowDelay && !hasDelay) delaySuggestions().tabComplete(last.substringAfter(':')).map { "-delay:$it" } else emptyList()
        }

        if (prev.equals("-delay", ignoreCase = true) && !last.startsWith("-")) {
            return if (allowDelay && !hasDelay) delaySuggestions().tabComplete(last) else emptyList()
        }

        return nextFlags.tabComplete(last)
    }

    private fun delaySuggestions(): List<String> = listOf("30s", "1m", "3m", "5m", "10m", "30", "60", "180")
}
