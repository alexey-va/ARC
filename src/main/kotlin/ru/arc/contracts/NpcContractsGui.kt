package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NpcContractsGui {
    private val groupPattern = Regex("[a-z0-9][a-z0-9_-]{2,47}")

    private val contractGuiConfig: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/contracts.yml")
    }

    fun openList(player: Player, group: String) {
        if (!groupPattern.matches(group)) {
            player.sendActionBar(TextUtil.mm("<red>Эта книга заказов настроена неверно."))
            return
        }
        val policy = ContractRankPolicyResolver.resolve(player)
        openList(player, group, policy)
    }

    fun openDetail(
        player: Player,
        group: String,
        contractId: String,
        requestedQuantity: Int? = null,
    ) {
        val policy = ContractRankPolicyResolver.resolve(player)
        openDetail(player, group, contractId, requestedQuantity, policy)
    }

    private fun openList(player: Player, group: String, policy: ContractRankPolicy) {
        val views =
            ContractsManager.currentPlayerViews(player.uniqueId, group, policy = policy)
                .filter { it.contract.status != ContractStatus.EXPIRED.label }
        val capacity = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACTS_LIST)
            .region(ArcMenuSchema.CONTRACT_ORDERS).size
        val orders = views.take(capacity).map { view -> orderEntry(player, group, view) }
        val elements = buildMap {
            put(
                "info",
                ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_LIST,
                        "info",
                        render(
                            "heading" to boardString(group, "list.heading", "<gold><bold>Книга заказов"),
                            "description-one" to boardString(group, "list.description-1", "<gray>Здесь принимают нужные ресурсы"),
                            "description-two" to boardString(group, "list.description-2", "<gray>за указанную награду."),
                        ),
                    ),
                ),
            )
            if (orders.isEmpty()) {
                put(
                    "empty",
                    ArcMenus.entry(
                        ArcMenus.item(
                            ArcMenuSchema.CONTRACTS_LIST,
                            "empty",
                            render("empty" to boardString(group, "list.empty-lore", "<gray>Загляните сюда позже.")),
                        ),
                    ),
                )
            }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.CONTRACTS_LIST,
            TextUtil.mm(boardString(group, "list.title", "<dark_gray>Книга заказов"), true),
            elements = elements,
            regions = mapOf(ArcMenuSchema.CONTRACT_ORDERS to orders),
        )
    }

    private fun orderEntry(player: Player, group: String, view: ResourceContractPlayerView): PaperMenuEntry {
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selectable = ContractQuantitySelector.select(view, available)
        val item = ArcMenus.item(
            "contracts-order",
            render(
                "contract-name" to view.contract.displayName,
                "available" to available.toString(),
                "remaining" to view.contract.remainingQuantity.toString(),
                "target" to view.contract.targetQuantity.toString(),
                "player-remaining" to view.playerRemainingQuantity.toString(),
                "payout" to formatContractMoney(view.playerPayoutMinorPerUnit),
                "cap-bonus" to ((view.capBasisPoints / 100) - 100).toString(),
                "payout-bonus" to ((view.payoutBasisPoints / 100) - 100).toString(),
                "ends-at" to formatTime(view.contract.windowEndsAt),
                "action" to action(view, selectable),
            ),
        ).withType(PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER)
        return ArcMenus.entry(item) {
            if (selectable.canSubmit && view.contract.status == ContractStatus.OPEN.label) {
                openDetail(it, group, view.contract.id)
            } else {
                it.sendActionBar(unavailableReason(group, view, selectable, available))
            }
        }
    }

    private fun openDetail(
        player: Player,
        group: String,
        contractId: String,
        requestedQuantity: Int?,
        policy: ContractRankPolicy,
    ) {
        val view =
            ContractsManager.currentPlayerViews(player.uniqueId, group, policy = policy)
                .firstOrNull { it.contract.id == contractId }
                ?: return openList(player, group, policy)
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selection = ContractQuantitySelector.select(view, available, requestedQuantity)
        val material = PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER
        val canSubmit = selection.canSubmit
        ArcMenus.open(
            player,
            ArcMenuSchema.CONTRACTS_DETAIL,
            TextUtil.mm(boardString(group, "detail.title", "<dark_gray>Сдать ресурсы"), true),
            elements = mapOf(
                "resource" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_DETAIL,
                        "resource",
                        render(
                            "contract-name" to view.contract.displayName,
                            "available" to available.toString(),
                            "remaining" to view.contract.remainingQuantity.toString(),
                            "player-remaining" to view.playerRemainingQuantity.toString(),
                        ),
                    ).withType(material),
                ),
                "info" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_DETAIL,
                        "info",
                        render(
                            "heading" to boardString(group, "detail.info-heading", "<gold><bold>Общий заказ"),
                            "accepted" to view.contract.acceptedQuantity.toString(),
                            "target" to view.contract.targetQuantity.toString(),
                            "contributors" to view.contract.contributors.toString(),
                        ),
                    ),
                ),
                "payout" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_DETAIL,
                        "payout",
                        render(
                            "payout" to formatContractMoney(selection.payoutMinor),
                            "per-unit" to formatContractMoney(view.playerPayoutMinorPerUnit),
                            "payout-bonus" to ((view.payoutBasisPoints / 100) - 100).toString(),
                        ),
                    ),
                ),
                "quantity" to ArcMenus.entryWithContext(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_DETAIL,
                        "quantity",
                        render(
                            "selected" to selection.selected.toString(),
                            "minimum" to selection.minimum.toString(),
                            "maximum" to selection.maximum.toString(),
                        ),
                    ).withType(material),
                ) { context ->
                    val event = context.event
                    if (!event.isLeftClick && !event.isRightClick) return@entryWithContext
                    if (canSubmit) {
                        openDetail(
                            context.player,
                            group,
                            contractId,
                            ContractQuantitySelector.adjust(
                                selection,
                                decrease = event.isRightClick,
                                jumpToBoundary = event.isShiftClick,
                            ),
                        )
                    } else {
                        context.player.sendActionBar(unavailableReason(group, view, selection, available))
                    }
                },
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "back")) {
                    openList(it, group)
                },
                "confirm" to ArcMenus.entry(
                    ArcMenus.item(
                        ArcMenuSchema.CONTRACTS_DETAIL,
                        "confirm",
                        PaperMenuItemRenderContext(
                            values = mapOf(
                                "selected" to Component.text(selection.selected),
                                "payout" to Component.text(formatContractMoney(selection.payoutMinor)),
                            ),
                            flags = if (canSubmit) setOf("can-submit") else emptySet(),
                        ),
                    ),
                    enabled = canSubmit,
                ) { submit(it, group, contractId, selection.selected) },
            ),
        )
    }

    private fun render(vararg values: Pair<String, String>) = PaperMenuItemRenderContext(
        values = values.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

    private fun submit(
        player: Player,
        group: String,
        contractId: String,
        quantity: Int,
    ) {
        player.closeInventory()
        player.sendActionBar(message(group, "messages.processing", "<gray>Проверяем ресурсы и запись в книге…"))
        ContractsManager.submit(player, contractId, quantity).whenComplete { outcome, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (!player.isOnline) return@Runnable
                    if (failure != null || outcome == null) {
                        player.sendMessage(
                            message(
                                group,
                                "messages.failure",
                                "<red>Заказ остановлен для проверки. <gray>Предметы повторно не сдавайте.",
                            ),
                        )
                        return@Runnable
                    }
                    player.sendMessage(ContractPlayerMessages.render(outcome, contractGuiConfig, group))
                    if (outcome !is ContractSubmissionOutcome.ManualReview) openList(player, group)
                },
            )
        }
    }

    private fun unavailableReason(
        group: String,
        view: ResourceContractPlayerView,
        selection: ContractQuantitySelection,
        available: Int,
    ): Component =
        when {
            view.contract.status != ContractStatus.OPEN.label ->
                message(group, "messages.closed", "<yellow>Этот заказ сейчас закрыт.")
            view.playerRemainingQuantity < view.minSubmissionQuantity ->
                message(group, "messages.player-cap", "<yellow>Ваш лимит по этому заказу исчерпан.")
            view.contract.remainingQuantity < view.minSubmissionQuantity ->
                message(group, "messages.completed", "<yellow>Нужный объём уже собран.")
            available < selection.minimum ->
                message(group, "messages.not-enough-items", "<yellow>Не хватает минимальной партии обычных предметов.")
            else -> message(group, "messages.unavailable", "<yellow>Сдача этого заказа сейчас недоступна.")
        }

    private fun action(
        view: ResourceContractPlayerView,
        selection: ContractQuantitySelection,
    ): String =
        when {
            view.contract.status != ContractStatus.OPEN.label -> "<yellow>Заказ сейчас закрыт"
            view.playerRemainingQuantity < view.minSubmissionQuantity -> "<yellow>Ваш лимит исчерпан"
            view.contract.remainingQuantity < view.minSubmissionQuantity -> "<green>Заказ выполнен"
            !selection.canSubmit -> "<yellow>Не хватает минимальной партии"
            else -> "<green>Нажмите, чтобы выбрать количество"
        }

    private fun message(group: String, path: String, fallback: String): Component =
        TextUtil.mm(boardString(group, path, fallback))

    private fun boardString(group: String, path: String, fallback: String): String =
        contractGuiConfig.string("boards.$group.$path", contractGuiConfig.string("defaults.$path", fallback))

    private fun formatTime(timestamp: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))

    private val TIME_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'", java.util.Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.of("Europe/Moscow"))
}

object ContractPlayerMessages {
    fun render(outcome: ContractSubmissionOutcome, config: Config, group: String): Component =
        when (outcome) {
            is ContractSubmissionOutcome.Committed ->
                message(
                    config,
                    "messages.committed",
                    group,
                    "<gold><speaker> <dark_gray>» <green>Заказ принят. <gray>Сдано <white><quantity><gray>, выплата <gold><payout> <white>💰</white><gray>.",
                    "quantity" to outcome.receipt.quantity,
                    "payout" to formatContractMoney(outcome.receipt.payoutMinor),
                )
            is ContractSubmissionOutcome.Duplicate ->
                message(
                    config,
                    "messages.duplicate",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Эта сдача уже учтена. <gray>Повторной выплаты не было.",
                )
            is ContractSubmissionOutcome.Rejected ->
                message(
                    config,
                    "messages.rejected",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Заказ не принят: <gray><reason>.",
                    "reason" to rejection(config, group, outcome.reason),
                )
            is ContractSubmissionOutcome.Cancelled ->
                message(
                    config,
                    "messages.cancelled",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Инвентарь изменился. <gray>Предметы и деньги не менялись.",
                )
            is ContractSubmissionOutcome.Refunded ->
                message(
                    config,
                    "messages.refunded",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Выплата не прошла. <gray>Сданные предметы возвращены.",
                )
            is ContractSubmissionOutcome.ManualReview ->
                message(
                    config,
                    "messages.manual-review",
                    group,
                    "<red>Операция остановлена для проверки. <gray>Не повторяйте сдачу до разбора администратором.",
                )
            is ContractSubmissionOutcome.Unavailable ->
                message(
                    config,
                    "messages.unavailable",
                    group,
                    "<yellow>Книга заказов сейчас недоступна. <gray>Предметы и деньги не менялись.",
                )
        }

    private fun rejection(config: Config, group: String, reason: SubmissionRejection): String =
        config.string(
            "boards.$group.messages.rejections.${reason.label}",
            config.string(
                "defaults.messages.rejections.${reason.label}",
                when (reason) {
                    SubmissionRejection.INVALID_REQUEST -> "неверные параметры"
                    SubmissionRejection.CONTRACT_NOT_OPEN -> "заказ сейчас закрыт"
                    SubmissionRejection.WINDOW_MISMATCH -> "период заказа изменился"
                    SubmissionRejection.STALE_STATE -> "состояние заказа изменилось"
                    SubmissionRejection.QUANTITY_EXHAUSTED -> "нужный объём уже собран"
                    SubmissionRejection.BUDGET_EXHAUSTED -> "бюджет заказа исчерпан"
                    SubmissionRejection.PLAYER_CAP_REACHED -> "ваш лимит исчерпан"
                    SubmissionRejection.CONTRIBUTOR_LIMIT_REACHED -> "достигнут лимит участников"
                    SubmissionRejection.BELOW_MINIMUM -> "количество меньше минимальной партии"
                    SubmissionRejection.PROJECT_STAGE_LOCKED -> "этот этап ещё не открыт"
                    SubmissionRejection.INVENTORY_UNAVAILABLE -> "не хватает обычных предметов без модификаций"
                    SubmissionRejection.JOURNAL_CAPACITY_REACHED -> "журнал операций временно заполнен"
                    SubmissionRejection.SUBMISSION_IN_PROGRESS -> "предыдущая сдача ещё обрабатывается"
                },
            ),
        )

    private fun message(
        config: Config,
        path: String,
        group: String,
        fallback: String,
        vararg tags: Pair<String, Any>,
    ): Component {
        val resolver = TagResolver.builder()
            .resolver(
                TagResolver.resolver(
                    "speaker",
                    Tag.inserting(TextUtil.mm(config.string("boards.$group.speaker", "Приёмщик"))),
                ),
            )
        tags.forEach { (name, value) ->
            resolver.resolver(TagResolver.resolver(name, Tag.inserting(Component.text(value.toString()))))
        }
        return TextUtil.mm(
            config.string("boards.$group.$path", config.string("defaults.$path", fallback)),
            resolver.build(),
        )
    }

}
