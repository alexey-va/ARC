package ru.arc.contracts

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.gui.gui
import ru.arc.util.GuiUtils
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
        GuiUtils.constructAndShowAsync({ buildList(player, group) }, player)
    }

    fun openDetail(
        player: Player,
        group: String,
        contractId: String,
        requestedQuantity: Int? = null,
    ) {
        GuiUtils.constructAndShowAsync({ buildDetail(player, group, contractId, requestedQuantity) }, player)
    }

    internal fun buildList(player: Player, group: String): ChestGui {
        val views =
            ContractsManager.currentPlayerViews(player.uniqueId, group)
                .filter { it.contract.status != ContractStatus.EXPIRED.label }
        return gui(boardString(group, "list.title", "<dark_gray>Книга заказов"), 5, player, contractGuiConfig) {
            background()
            staticPane(width = 9, height = 4) {
                item(4, 0) {
                    material(Material.PAPER)
                    display(boardString(group, "list.heading", "<gold><bold>Книга заказов"))
                    lore(
                        listOf(
                            boardString(group, "list.description-1", "<gray>Здесь принимают нужные ресурсы"),
                            boardString(group, "list.description-2", "<gray>за указанную награду."),
                            "",
                            "<white>Выберите заказ ниже.",
                        ),
                    )
                    fromConfig(contractGuiConfig, "list.info")
                }

                if (views.isEmpty()) {
                    item(4, 2) {
                        material(Material.BARRIER)
                        display("<yellow>Сейчас заказов нет")
                        lore(listOf(boardString(group, "list.empty-lore", "<gray>Загляните сюда позже.")))
                        fromConfig(contractGuiConfig, "list.empty")
                    }
                } else {
                    views.take(LIST_POSITIONS.size).forEachIndexed { index, view ->
                        val (x, y) = LIST_POSITIONS[index]
                        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
                        val selectable = ContractQuantitySelector.select(view, available)
                        item(x, y) {
                            material(PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER)
                            tags(
                                mapOf(
                                    "contract_name" to view.contract.displayName,
                                    "available" to available.toString(),
                                    "remaining" to view.contract.remainingQuantity.toString(),
                                    "target" to view.contract.targetQuantity.toString(),
                                    "player_remaining" to view.playerRemainingQuantity.toString(),
                                    "payout" to money(view.contract.payoutMinorPerUnit),
                                    "ends_at" to formatTime(view.contract.windowEndsAt),
                                    "action" to action(view, selectable),
                                ),
                            )
                            display("<gold><bold><contract_name>")
                            lore(
                                listOf(
                                    "<gray>Осталось по заказу: <white><remaining><gray>/<white><target>",
                                    "<gray>У вас в инвентаре: <white><available>",
                                    "<gray>Ваш лимит: <white><player_remaining>",
                                    "<gray>Награда: <gold><payout> <gray>за единицу",
                                    "<gray>До: <white><ends_at>",
                                    "",
                                    "<action>",
                                ),
                            )
                            fromConfig(contractGuiConfig, "list.order")
                            onClick {
                                if (selectable.canSubmit && view.contract.status == ContractStatus.OPEN.label) {
                                    openDetail(player, group, view.contract.id)
                                } else {
                                    player.sendActionBar(unavailableReason(group, view, selectable, available))
                                }
                            }
                        }
                    }
                }
            }
            navBar {
                button(4) {
                    material(Material.BLACK_STAINED_GLASS_PANE)
                    display("<yellow><bold>Обновить")
                    lore(listOf("<gray>Перечитать книгу заказов."))
                    fromConfig(contractGuiConfig, "list.refresh")
                    onClick { openList(player, group) }
                }
                button(8) {
                    material(Material.RED_STAINED_GLASS_PANE)
                    display("<red><bold>Закрыть")
                    lore(emptyList())
                    fromConfig(contractGuiConfig, "list.close")
                    onClick { player.closeInventory() }
                }
            }
        }
    }

    internal fun buildDetail(
        player: Player,
        group: String,
        contractId: String,
        requestedQuantity: Int?,
    ): ChestGui {
        val view =
            ContractsManager.currentPlayerViews(player.uniqueId, group)
                .firstOrNull { it.contract.id == contractId }
                ?: return buildList(player, group)
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selection = ContractQuantitySelector.select(view, available, requestedQuantity)
        val material = PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER

        return gui(boardString(group, "detail.title", "<dark_gray>Сдать ресурсы"), 3, player, contractGuiConfig) {
            background()
            staticPane(width = 9, height = 2) {
                item(2, 0) {
                    material(material)
                    tags(
                        mapOf(
                            "contract_name" to view.contract.displayName,
                            "available" to available.toString(),
                            "remaining" to view.contract.remainingQuantity.toString(),
                            "player_remaining" to view.playerRemainingQuantity.toString(),
                        ),
                    )
                    display("<gold><bold><contract_name>")
                    lore(
                        listOf(
                            "<gray>У вас: <white><available>",
                            "<gray>Осталось по заказу: <white><remaining>",
                            "<gray>Ваш лимит: <white><player_remaining>",
                            "",
                            "<dark_gray>Принимаются только обычные предметы",
                            "<dark_gray>без имени, чар и особых данных.",
                        ),
                    )
                    fromConfig(contractGuiConfig, "detail.resource")
                }
                item(4, 0) {
                    material(Material.PAPER)
                    tags(
                        mapOf(
                            "accepted" to view.contract.acceptedQuantity.toString(),
                            "target" to view.contract.targetQuantity.toString(),
                            "contributors" to view.contract.contributors.toString(),
                        ),
                    )
                    display(boardString(group, "detail.info-heading", "<gold><bold>Общий заказ"))
                    lore(
                        listOf(
                            "<gray>Собрано: <white><accepted><gray>/<white><target>",
                            "<gray>Участников: <white><contributors>",
                            "",
                            "<gray>Объём общий для всего сервера.",
                        ),
                    )
                    fromConfig(contractGuiConfig, "detail.info")
                }
                item(6, 0) {
                    material(Material.GOLD_NUGGET)
                    tags(
                        mapOf(
                            "payout" to money(selection.payoutMinor),
                            "per_unit" to money(view.contract.payoutMinorPerUnit),
                        ),
                    )
                    display("<gold><bold>Выплата: <payout>")
                    lore(listOf("<gray>Ставка: <gold><per_unit> <gray>за единицу."))
                    fromConfig(contractGuiConfig, "detail.payout")
                }
                item(2, 1) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    display("<yellow><bold>Уменьшить")
                    lore(
                        listOf(
                            "<gray>ЛКМ: убрать минимальную партию.",
                            "<gray>Shift + ЛКМ: выбрать минимум.",
                        ),
                    )
                    fromConfig(contractGuiConfig, "detail.decrease")
                    onClick { event ->
                        if (selection.canSubmit) {
                            openDetail(
                                player,
                                group,
                                contractId,
                                ContractQuantitySelector.decrease(selection, event.isShiftClick),
                            )
                        }
                    }
                }
                item(4, 1) {
                    material(material)
                    tags(
                        mapOf(
                            "selected" to selection.selected.toString(),
                            "minimum" to selection.minimum.toString(),
                            "maximum" to selection.maximum.toString(),
                        ),
                    )
                    display("<white><bold>Сдать: <selected>")
                    lore(
                        listOf(
                            "<gray>Минимум: <white><minimum>",
                            "<gray>Сейчас доступно: <white><maximum>",
                        ),
                    )
                    fromConfig(contractGuiConfig, "detail.quantity")
                }
                item(6, 1) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    display("<yellow><bold>Увеличить")
                    lore(
                        listOf(
                            "<gray>ЛКМ: добавить минимальную партию.",
                            "<gray>Shift + ЛКМ: выбрать максимум.",
                        ),
                    )
                    fromConfig(contractGuiConfig, "detail.increase")
                    onClick { event ->
                        if (selection.canSubmit) {
                            openDetail(
                                player,
                                group,
                                contractId,
                                ContractQuantitySelector.increase(selection, event.isShiftClick),
                            )
                        }
                    }
                }
            }
            navBar {
                button(0) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    display("<yellow><bold>Назад")
                    lore(listOf("<gray>Вернуться к книге заказов."))
                    fromConfig(contractGuiConfig, "detail.back")
                    onClick { openList(player, group) }
                }
                button(4) {
                    material(Material.BLACK_STAINED_GLASS_PANE)
                    display("<yellow><bold>Выбрать максимум")
                    lore(listOf("<gray>Сдать всё доступное в пределах заказа."))
                    fromConfig(contractGuiConfig, "detail.maximum")
                    onClick {
                        if (selection.canSubmit) openDetail(player, group, contractId, selection.maximum)
                    }
                }
                button(8) {
                    material(Material.GREEN_STAINED_GLASS_PANE)
                    tags(
                        mapOf(
                            "selected" to selection.selected.toString(),
                            "payout" to money(selection.payoutMinor),
                        ),
                    )
                    display(if (selection.canSubmit) "<green><bold>Сдать <selected>" else "<red><bold>Сдача недоступна")
                    lore(
                        if (selection.canSubmit) {
                            listOf(
                                "<gray>Будет принято: <white><selected>",
                                "<gray>Выплата: <gold><payout>",
                                "",
                                "<green>ЛКМ: подтвердить сдачу.",
                            )
                        } else {
                            listOf("<gray>Не хватает минимальной партии.")
                        },
                    )
                    fromConfig(contractGuiConfig, "detail.confirm")
                    onClick {
                        if (selection.canSubmit) submit(player, group, contractId, selection.selected)
                        else player.sendActionBar(unavailableReason(group, view, selection, available))
                    }
                }
            }
        }
    }

    private fun submit(
        player: Player,
        group: String,
        contractId: String,
        quantity: Int,
    ) {
        player.closeInventory()
        player.sendActionBar(message(group, "messages.processing", "<gray>Проверяем ресурсы и запись в книге…"))
        ContractsManager.submit(player.uniqueId, contractId, quantity).whenComplete { outcome, failure ->
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

    private fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    private fun formatTime(timestamp: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))

    private val LIST_POSITIONS = listOf(0 to 1, 2 to 1, 4 to 1, 6 to 1, 8 to 1, 1 to 2, 3 to 2, 5 to 2, 7 to 2)

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
                    "<gold><speaker> <dark_gray>» <green>Заказ принят. <gray>Сдано <white><quantity><gray>, выплата <gold><payout>.",
                    "quantity" to outcome.receipt.quantity,
                    "payout" to money(outcome.receipt.payoutMinor),
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

    private fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
}
