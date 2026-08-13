package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.ContractsMode
import ru.arc.util.TextUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read-only public contract board while Economy V2 is in calibration. */
object ContractsSubCommand : SubCommand {
    override val configKey = "contracts"
    override val defaultName = "contracts"
    override val defaultPermission: String? = null
    override val defaultDescription = "Открыть доску ресурсных контрактов"
    override val defaultUsage = "/arc contracts [status|submit <id> <количество>]"
    override val defaultPlayerOnly = false

    override fun isAvailable(): Boolean = ContractsManager.mode() != ContractsMode.DISABLED

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull()?.lowercase() ?: "status"
        when (action) {
            "status", "list" -> showBoard(sender)
            "submit", "сдать" -> {
                if (ContractsManager.submissionsEnabled()) {
                    sender.sendMessage(TextUtil.mm("<red>Сдача ещё не активирована. Деньги и предметы не изменены."))
                } else {
                    sender.sendMessage(
                        CommandConfig.get(
                            "contracts.observe-only",
                            "<yellow>Контракты пока калибруются. <gray>Сдача предметов и выплаты выключены.",
                        ),
                    )
                }
            }
            else -> sendUsage(sender)
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("status", "submit").tabComplete(args[0])
            2 -> if (args[0].equals("submit", true)) ContractsManager.currentViews().map { it.id }.tabComplete(args[1]) else null
            else -> null
        }

    private fun showBoard(sender: CommandSender) {
        val views = ContractsManager.currentViews()
        sender.sendMessage(CommandConfig.get("contracts.header", "<gold>═══ Доска контрактов ═══"))
        if (views.isEmpty()) {
            sender.sendMessage(
                CommandConfig.get(
                    "contracts.calibration",
                    "<gray>Идёт калибровка экономики. Первые ресурсные заказы появятся после чистого периода наблюдений.",
                ),
            )
            val weeklyBudget = ContractsManager.summary()["serverWeeklyBudgetMinor"] as? Long ?: 0L
            sender.sendMessage(TextUtil.mm("<dark_gray>Недельный бюджет: <gray>${money(weeklyBudget)}"))
            return
        }
        views.forEach { view ->
            sender.sendMessage(
                TextUtil.mm(
                    "<yellow>${escape(view.displayName)} <dark_gray>[${status(view.status)}]\n" +
                        "<gray>  ${view.itemKey}: <white>${view.remainingQuantity}/${view.targetQuantity}" +
                        " <gray>· <green>${money(view.payoutMinorPerUnit)}/шт." +
                        " <gray>· до <white>${formatTime(view.windowEndsAt)}",
                ),
            )
        }
        if (!ContractsManager.submissionsEnabled()) {
            sender.sendMessage(CommandConfig.get("contracts.observe-only", "<yellow>Сдача предметов пока выключена: режим калибровки."))
        }
    }

    private fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    private fun formatTime(timestamp: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))

    private fun escape(value: String): String = value.replace("<", "‹").replace(">", "›")

    private fun status(value: String): String =
        when (value) {
            "open" -> "открыт"
            "paused" -> "на паузе"
            "completed" -> "выполнен"
            "expired" -> "завершён"
            else -> "неизвестно"
        }

    private val TIME_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'", java.util.Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.of("Europe/Moscow"))
}
