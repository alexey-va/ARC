package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.ContractSubmissionOutcome
import ru.arc.contracts.SubmissionRejection
import ru.arc.core.Tasks
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
            "submit", "сдать" -> submit(sender, args)
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

    private fun submit(sender: CommandSender, args: Array<String>) {
        if (!ContractsManager.submissionsEnabled()) {
            sender.sendMessage(
                CommandConfig.get(
                    "contracts.observe-only",
                    "<yellow>Контракты пока калибруются. <gray>Сдача предметов и выплаты выключены.",
                ),
            )
            return
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtil.mm("<red>Сдать ресурсы может только игрок."))
            return
        }
        val contractId = args.getOrNull(1)?.lowercase()
        val quantity = args.getOrNull(2)?.toIntOrNull()
        if (contractId == null || quantity == null || quantity <= 0) {
            sendUsage(sender)
            return
        }
        player.sendMessage(TextUtil.mm("<gray>Проверяю ресурсы и резерв контракта…"))
        ContractsManager.submit(player.uniqueId, contractId, quantity).whenComplete { outcome, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (!player.isOnline) return@Runnable
                    if (failure != null || outcome == null) {
                        player.sendMessage(TextUtil.mm("<red>Заявка остановлена. Не повторяй её до проверки администратора."))
                        return@Runnable
                    }
                    player.sendMessage(renderOutcome(outcome))
                },
            )
        }
    }

    private fun renderOutcome(outcome: ContractSubmissionOutcome) =
        TextUtil.mm(
            when (outcome) {
                is ContractSubmissionOutcome.Committed ->
                    "<green>Контракт принят: <white>${outcome.receipt.quantity} шт. <gray>· выплата <green>${money(outcome.receipt.payoutMinor)}"
                is ContractSubmissionOutcome.Duplicate ->
                    "<yellow>Эта заявка уже учтена. <gray>Повторной выплаты не было."
                is ContractSubmissionOutcome.Rejected ->
                    "<yellow>Заявка не принята: <gray>${rejection(outcome.reason)}"
                is ContractSubmissionOutcome.Cancelled ->
                    "<yellow>Инвентарь изменился до сдачи. <gray>Предметы и деньги не менялись."
                is ContractSubmissionOutcome.Refunded ->
                    "<yellow>Выплата не прошла. <gray>Точные сданные предметы возвращены."
                is ContractSubmissionOutcome.ManualReview ->
                    "<red>Заявка ${outcome.submissionId} остановлена для проверки. <gray>Не повторяй её вручную."
                is ContractSubmissionOutcome.Unavailable ->
                    "<red>Контрактный сервис сейчас недоступен. <gray>Предметы и деньги не менялись."
            },
        )

    private fun rejection(reason: SubmissionRejection): String =
        when (reason) {
            SubmissionRejection.INVALID_REQUEST -> "неверные параметры"
            SubmissionRejection.CONTRACT_NOT_OPEN -> "заказ сейчас закрыт"
            SubmissionRejection.WINDOW_MISMATCH -> "окно заказа изменилось"
            SubmissionRejection.STALE_STATE -> "состояние заказа изменилось, открой доску заново"
            SubmissionRejection.QUANTITY_EXHAUSTED -> "нужное количество уже собрано"
            SubmissionRejection.BUDGET_EXHAUSTED -> "бюджет заказа исчерпан"
            SubmissionRejection.PLAYER_CAP_REACHED -> "твой лимит по заказу исчерпан"
            SubmissionRejection.CONTRIBUTOR_LIMIT_REACHED -> "лимит участников заказа исчерпан"
            SubmissionRejection.BELOW_MINIMUM -> "количество меньше минимальной партии"
            SubmissionRejection.PROJECT_STAGE_LOCKED -> "этот этап общего проекта ещё не открыт"
            SubmissionRejection.INVENTORY_UNAVAILABLE -> "нет нужного количества обычных предметов без модификаций"
            SubmissionRejection.JOURNAL_CAPACITY_REACHED -> "журнал заявок заполнен; нужна проверка администратора"
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
