package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.audit.AuditFilter
import ru.arc.audit.AuditManager
import ru.arc.commands.arc.*
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
    override val defaultDescription = "Просмотр и управление аудитом игроков"
    override val defaultUsage = "/arc audit <player|clearall> [page] [filter]"

    private val filters = AuditFilter.entries.map { it.name.lowercase() }

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val firstArg = args[0]

        // /arc audit clearall
        if (firstArg.equals("clearall", ignoreCase = true)) {
            AuditManager.clearAll()
            sender.sendMessage(CommandConfig.auditCleared())
            return true
        }

        val playerName = firstArg

        // /arc audit <player> clear
        if (args.size >= 2 && args[1].equals("clear", ignoreCase = true)) {
            AuditManager.clear(playerName)
            sender.sendMessage(CommandConfig.auditClearedFor(playerName))
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
            1 -> (listOf("clearall") + PlayerManager.getPlayerNames()).tabComplete(args[0])
            2 -> (listOf("1", "2", "3", "4", "5", "clear") + filters).tabComplete(args[1])
            3 -> filters.tabComplete(args[2])
            else -> null
        }
    }
}
