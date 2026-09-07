package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.audit.AuditFilter
import ru.arc.audit.AuditManager
import ru.arc.commands.arc.*
import ru.arc.core.Tasks
import ru.arc.metrics.MetricsModule
import ru.arc.util.Logging.warn
import ru.arc.xserver.playerlist.PlayerManager

/**
 * /arc audit - просмотр и управление аудитом игроков.
 *
 * Использование:
 * - clearall - очистить весь аудит
 * - <player> [page] [filter] - просмотр аудита игрока
 * - <player> clear - очистить аудит игрока
 */
object AuditSubCommand : SubCommand {

    override val configKey = "audit"
    override val defaultName = "audit"
    override val defaultPermission = "arc.audit"
    override val defaultDescription = "Просмотр аудита транзакций игрока с фильтрацией по типу"
    override val defaultUsage = "/arc audit <player> [page] [filter] | <player> clear | clearall | reset-period [status|confirm <generation>]"

    private val filters = AuditFilter.entries.map { it.name.lowercase() }

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val firstArg = args[0]

        if (firstArg.equals("reset-period", ignoreCase = true)) {
            if (!sender.hasPermission("arc.audit.reset")) {
                sender.sendMessage(CommandConfig.noPermission())
                return true
            }
            val confirm = args.size == 3 && args[1].equals("confirm", true)
            val generation = args.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 }
            if (confirm && generation != null) {
                MetricsModule.resetMeasurementPeriod(generation).whenComplete { state, failure ->
                    if (failure != null) warn("Measurement period request failed for generation {}", generation, failure)
                    Tasks.scheduler.runSync(Runnable {
                        if (failure == null) sender.sendMessage(CommandConfig.get("audit.reset-period-success", "<green>Новый период начат: <white>%boundary%<green>. Предыдущие данные сохранены.", "%boundary%", state.boundaryAt.toString()))
                        else sender.sendMessage(CommandConfig.get("audit.reset-period-failed", "<red>Запрос не завершён. Проверьте /arc audit reset-period status и журнал сервера."))
                    })
                }
            } else if (args.size == 1 || (args.size == 2 && args[1].equals("status", true))) {
                MetricsModule.readMeasurementReset().whenComplete { state, failure ->
                    if (failure != null) warn("Measurement period status read failed", failure)
                    Tasks.scheduler.runSync(Runnable {
                        if (failure != null) {
                            sender.sendMessage(CommandConfig.get("audit.reset-period-failed", "<red>Запрос не завершён. Проверьте /arc audit reset-period status и журнал сервера."))
                        } else {
                            sender.sendMessage(CommandConfig.get("audit.reset-period-status", "<gray>Начало периода: <white>%boundary%<gray>; поколение <white>%generation%", "%boundary%", state?.boundaryAt?.toString() ?: "—", "%generation%", (state?.generation ?: 0).toString()))
                            if (args.size == 1) sender.sendMessage(CommandConfig.get("audit.reset-period-confirm", "<gray>Отчёты начнут новый период; старые данные останутся в архиве. Подтвердить: <white>/arc audit reset-period confirm %generation%", "%generation%", (state?.generation ?: 0).toString()))
                        }
                    })
                }
            } else sendUsage(sender)
            return true
        }

        // /arc audit clearall
        if (firstArg.equals("clearall", ignoreCase = true)) {
            completeClear(sender, AuditManager.clearAll()) { CommandConfig.auditCleared() }
            return true
        }

        val playerName = firstArg

        // /arc audit <player> clear
        if (args.size >= 2 && args[1].equals("clear", ignoreCase = true)) {
            completeClear(sender, AuditManager.clear(playerName)) { CommandConfig.auditClearedFor(playerName) }
            return true
        }

        // Парсим страницу
        val page = args.getOrNull(1)?.toIntOrNull() ?: run {
            if (args.size >= 2 && !filters.contains(args[1].lowercase())) {
                sender.sendMessage(CommandConfig.auditInvalidPage(args[1]))
                return true
            }
            1
        }

        // Парсим фильтр (может быть 2-м или 3-м аргументом)
        val filterArg = args.getOrNull(2) ?: args.getOrNull(1)?.takeIf { filters.contains(it.lowercase()) }
        val filter = filterArg?.let { AuditFilter.fromString(it) } ?: AuditFilter.ALL

        AuditManager.sendAudit(sender, playerName, page, filter)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> (listOf("clearall") + (if (sender.hasPermission("arc.audit.reset")) listOf("reset-period") else emptyList()) + PlayerManager.getPlayerNames()).tabComplete(args[0])
            2 -> if (args[0].equals("reset-period", true)) listOf("status", "confirm").tabComplete(args[1]) else (listOf("1", "2", "3", "4", "5", "clear") + filters).tabComplete(args[1])
            3 -> filters.tabComplete(args[2])
            else -> null
        }
    }

    private fun completeClear(
        sender: CommandSender,
        completion: java.util.concurrent.CompletableFuture<Int>,
        success: () -> net.kyori.adventure.text.Component,
    ) {
        if (completion.isDone) {
            runCatching(completion::join)
                .onSuccess { sender.sendMessage(success()) }
                .onFailure { sender.sendMessage("Audit clear failed; see server log") }
            return
        }
        completion.whenComplete { _, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (failure == null) sender.sendMessage(success())
                    else sender.sendMessage("Audit clear failed; see server log")
                },
            )
        }
    }
}
