package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.NpcContractsGui
import ru.arc.contracts.PaperSeasonTrophyItems
import ru.arc.contracts.SeasonDungeonLaunchPreparationOutcome
import ru.arc.contracts.SeasonMoneyActionOutcome
import ru.arc.contracts.SeasonMoneyActionRequest
import ru.arc.contracts.SeasonMoneyRejection
import ru.arc.contracts.SeasonTrophyContributionOutcome
import ru.arc.contracts.SeasonTrophyContributionRejection
import ru.arc.contracts.formatContractMoney
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.util.TextUtil
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Public contract status plus NPC-owned grouped resource menus. */
object ContractsSubCommand : SubCommand {
    override val configKey = "contracts"
    override val defaultName = "contracts"
    override val defaultPermission: String? = null
    override val defaultDescription = "Открыть доску ресурсных контрактов"
    override val defaultUsage =
        "/arc contracts [status|open <группа>|donate <этап> <сумма>|pass <данж>|launch <данж>|trophy <количество>]"
    override val defaultPlayerOnly = false

    override fun isAvailable(): Boolean = ContractsManager.mode() != ContractsMode.DISABLED

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull()?.lowercase() ?: "status"
        when (action) {
            "status", "list" -> showBoard(sender)
            "open", "menu", "меню" -> openBoard(sender, args)
            "donate", "вклад" -> donateCash(sender, args)
            "pass", "пропуск" -> buyPass(sender, args)
            "launch", "экспедиция" -> launchDungeon(sender, args)
            "trophy", "трофей" -> submitTrophy(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("status", "open", "donate", "pass", "launch", "trophy").tabComplete(args[0])
            2 ->
                when (args[0].lowercase()) {
                    "open" -> ContractsManager.currentViews().map { it.group }.distinct().tabComplete(args[1])
                    "donate" -> ContractsManager.seasonProjectStageIds().tabComplete(args[1])
                    "pass", "launch" -> ContractsManager.seasonDungeonContractIds().tabComplete(args[1])
                    else -> null
                }
            else -> null
        }

    private fun openBoard(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtil.mm("<red>Открыть книгу заказов может только игрок."))
            return
        }
        val group = args.getOrNull(1)?.trim()?.lowercase()
        if (group == null || ContractsManager.currentViews().none { it.group == group }) {
            player.sendActionBar(TextUtil.mm("<yellow>Эта книга заказов сейчас пуста."))
            return
        }
        NpcContractsGui.openList(player, group)
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
            sender.sendMessage(
                TextUtil.mm("<dark_gray>Недельный бюджет: <gray>${formatContractMoney(weeklyBudget)} <white>💰</white>"),
            )
            return
        }
        views.forEach { view ->
            sender.sendMessage(
                TextUtil.mm(
                    "<yellow>${escape(view.displayName)} <dark_gray>[${status(view.status)}]\n" +
                        "<gray>  ${view.itemKey}: <white>${view.remainingQuantity}/${view.targetQuantity}" +
                        " <gray>· <green>${formatContractMoney(view.payoutMinorPerUnit)} <white>💰</white><gray>/шт." +
                        " <gray>· до <white>${formatTime(view.windowEndsAt)}",
                ),
            )
        }
        if (!ContractsManager.submissionsEnabled()) {
            sender.sendMessage(CommandConfig.get("contracts.observe-only", "<yellow>Сдача предметов пока выключена: режим калибровки."))
        }
    }

    private fun donateCash(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val stageId = args.getOrNull(1)?.trim()?.lowercase()
        val amountMinor = args.getOrNull(2)?.let(::parseMoneyMinor)
        if (stageId == null || amountMinor == null) {
            sendUsage(sender)
            return
        }
        player.sendMessage(TextUtil.mm("<gray>Фиксирую денежный вклад в общий проект…"))
        ContractsManager.submitSeasonMoney(player.uniqueId, SeasonMoneyActionRequest.ProjectCash(stageId, amountMinor))
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderSeasonMoneyOutcome(outcome)
                }
            }
    }

    private fun buyPass(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val dungeonId = args.getOrNull(1)?.trim()?.lowercase()
        if (dungeonId == null) {
            sendUsage(sender)
            return
        }
        player.sendMessage(TextUtil.mm("<gray>Проверяю доступ и оплачиваю одноразовый вход…"))
        ContractsManager.submitSeasonMoney(player.uniqueId, SeasonMoneyActionRequest.DungeonAdmission(dungeonId))
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderSeasonMoneyOutcome(outcome)
                }
            }
    }

    private fun submitTrophy(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender, trophy = true) ?: return
        val quantity = args.getOrNull(1)?.toIntOrNull()
        val itemKey = PaperSeasonTrophyItems.identity(player.inventory.itemInMainHand)
        val stageId = ContractsManager.seasonCompletionStageId()
        if (quantity == null || quantity <= 0 || itemKey == null || stageId == null) {
            player.sendMessage(TextUtil.mm("<yellow>Возьми связанный трофей в основную руку и укажи количество."))
            return
        }
        player.sendMessage(TextUtil.mm("<gray>Передаю точные связанные трофеи в музей…"))
        ContractsManager.submitSeasonTrophy(player.uniqueId, stageId, itemKey, quantity)
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderTrophyOutcome(outcome)
                }
            }
    }

    private fun launchDungeon(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val dungeonId = args.getOrNull(1)?.trim()?.lowercase()
        val blueprintWorld = dungeonId?.let(ContractsManager::seasonDungeonBlueprintWorld)
        val emHook = HookRegistry.emHook
        if (dungeonId == null || blueprintWorld == null || emHook == null || !emHook.canLaunchSeasonDungeon(player, blueprintWorld)) {
            player.sendMessage(TextUtil.mm("<yellow>Этот сезонный данж сейчас недоступен для запуска."))
            return
        }
        val participants =
            HookRegistry.partiesHook?.localLaunchParticipants(player.uniqueId) ?: if (HookRegistry.partiesHook == null) {
                setOf(player.uniqueId)
            } else {
                player.sendMessage(TextUtil.mm("<yellow>Экспедицию запускает лидер пати."))
                return
            }
        player.sendMessage(TextUtil.mm("<gray>Проверяю одноразовые пропуски участников…"))
        ContractsManager.prepareSeasonDungeonLaunch(dungeonId, participants).whenComplete { outcome, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (!player.isOnline) {
                        if (outcome is SeasonDungeonLaunchPreparationOutcome.Ready) {
                            ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                        }
                        return@Runnable
                    }
                    if (failure != null || outcome !is SeasonDungeonLaunchPreparationOutcome.Ready) {
                        val message =
                            if (outcome is SeasonDungeonLaunchPreparationOutcome.Rejected) {
                                "<yellow>Экспедиция не запущена: <gray>${escape(outcome.code)}"
                            } else {
                                "<red>Запуск экспедиции сейчас недоступен."
                            }
                        player.sendMessage(TextUtil.mm(message))
                        return@Runnable
                    }
                    try {
                        if (!emHook.canLaunchSeasonDungeon(player, blueprintWorld)) {
                            ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                            player.sendMessage(TextUtil.mm("<yellow>Данж перестал быть доступен; пропуски освобождены."))
                            return@Runnable
                        }
                        emHook.launchSeasonDungeon(player, blueprintWorld)
                    } catch (_: Throwable) {
                        ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                        player.sendMessage(TextUtil.mm("<red>EliteMobs не принял запуск; пропуски освобождаются."))
                    }
                },
            )
        }
    }

    private fun requireSeasonPlayer(sender: CommandSender, trophy: Boolean = false): Player? {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtil.mm("<red>Это действие доступно только игроку."))
            return null
        }
        val enabled = if (trophy) ContractsManager.seasonTrophyEnabled() else ContractsManager.seasonMoneyEnabled()
        if (!enabled) {
            player.sendMessage(TextUtil.mm("<yellow>Сезонные операции пока выключены: идёт безопасная калибровка."))
            return null
        }
        return player
    }

    private fun syncReply(player: Player, message: () -> net.kyori.adventure.text.Component) {
        Tasks.scheduler.runSync(Runnable { if (player.isOnline) player.sendMessage(message()) })
    }

    private fun renderSeasonMoneyOutcome(outcome: SeasonMoneyActionOutcome) =
        TextUtil.mm(
            when (outcome) {
                is SeasonMoneyActionOutcome.Committed ->
                    "<green>Операция учтена: <white>${formatContractMoney(outcome.receipt.amountMinor)} <white>💰</white>"
                is SeasonMoneyActionOutcome.Duplicate -> "<yellow>Эта операция уже учтена; повторного списания не было."
                is SeasonMoneyActionOutcome.Rejected -> "<yellow>Операция отклонена: <gray>${seasonRejection(outcome.reason)}"
                is SeasonMoneyActionOutcome.Cancelled -> "<yellow>Списание не выполнено; баланс не изменился."
                is SeasonMoneyActionOutcome.ManualReview -> seasonFailureText()
                is SeasonMoneyActionOutcome.Unavailable -> "<red>Сезонный сервис сейчас недоступен; деньги не списывались."
            },
        )

    private fun renderTrophyOutcome(outcome: SeasonTrophyContributionOutcome) =
        TextUtil.mm(
            when (outcome) {
                is SeasonTrophyContributionOutcome.Committed ->
                    "<green>Музей принял <white>${outcome.receipt.quantity}</white> троф."
                is SeasonTrophyContributionOutcome.Duplicate -> "<yellow>Этот вклад уже учтён; трофеи повторно не снимались."
                is SeasonTrophyContributionOutcome.Rejected ->
                    "<yellow>Трофеи не приняты: <gray>${trophyRejection(outcome.reason)}"
                is SeasonTrophyContributionOutcome.Cancelled -> "<yellow>Инвентарь изменился; трофеи не сняты."
                is SeasonTrophyContributionOutcome.ManualReview -> seasonFailureText()
                is SeasonTrophyContributionOutcome.Unavailable -> "<red>Музей сейчас недоступен; трофеи не сняты."
            },
        )

    private fun seasonFailure() = TextUtil.mm(seasonFailureText())

    private fun seasonFailureText() =
        "<red>Операция остановлена для проверки. <gray>Не повторяй её до разбора администратором."

    private fun seasonRejection(reason: SeasonMoneyRejection): String = reason.label.replace('_', ' ')

    private fun trophyRejection(reason: SeasonTrophyContributionRejection): String = reason.label.replace('_', ' ')

    private fun parseMoneyMinor(raw: String): Long? =
        runCatching {
            BigDecimal(raw.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact().takeIf { it > 0L }
        }.getOrNull()

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
