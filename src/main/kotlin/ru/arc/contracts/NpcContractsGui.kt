package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.util.Common
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuElementId
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

    private var tracking: ContractTrackingRuntime? = null
    private var tasks: LifecycleTaskScope? = null

    fun start() {
        shutdown()
        tasks = LifecycleTaskScope()
        val redis = ARC.redisManager ?: return
        tracking = ContractTrackingRuntime(
            ARC.instance, RedisContractTrackingStore(redis, Common.gson),
            currentViews = { playerId, now ->
                val player = ARC.instance.server.getPlayer(playerId)
                if (player == null) emptyList() else ContractsManager.currentPlayerViews(
                    playerId, now = now, policy = ContractRankPolicyResolver.resolve(player),
                )
            },
            presentation = ContractTrackingPresentation(
                showProgress = { player, status ->
                    val view = ContractsManager.currentViews().firstOrNull { it.id == status.state.contractId }
                    if (view != null) player.sendActionBar(trackingText("progress", view.displayName, status))
                },
                notifyCompleted = { player, status ->
                    val view = ContractsManager.currentViews().firstOrNull { it.id == status.state.contractId }
                    if (view != null) player.sendMessage(trackingText("completed", view.displayName, status))
                },
            ),
        ).also { it.start() }
    }

    fun shutdown() {
        tasks?.close()
        tasks = null
        tracking?.close()
        tracking = null
    }

    private fun trackingText(key: String, name: String, status: ContractTrackingStatus): Component = TextUtil.mm(
        boardString("all", "tracking.$key", if (key == "completed")
            "<green>Ресурсы собраны: {name} · {current}/{target}. <gray>Проверьте условия в книге заказов."
        else "<aqua>{name} <white>{current}/{target} <gray>для сдачи")
            .replace("{name}", name).replace("{current}", status.currentQuantity.toString())
            .replace("{target}", status.targetQuantity.toString()),
    )

    private fun trackingEntry(player: Player, view: ResourceContractPlayerView, selection: ContractQuantitySelection,
                              browseGroup: String): PaperMenuEntry {
        val runtime = tracking
        val status = runtime?.status(player, view)
        val target = runCatching { ContractTrackingLogic.targetQuantity(view,
            if (selection.canSubmit) selection.selected.toLong() else (PaperContractItems.material(view.contract.itemKey)?.maxStackSize ?: 64).toLong(),
        ) }.getOrNull()
        val now = System.currentTimeMillis()
        val canTrack = runtime != null && target != null && now in view.contract.windowStartsAt until view.contract.windowEndsAt &&
            view.contract.status == ContractStatus.OPEN.label
        val textKey = if (status != null) "stop" else if (canTrack) "start" else "unavailable"
        return ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "track", PaperMenuItemRenderContext(
            values = mapOf(
                "target" to Component.text(status?.targetQuantity ?: target ?: 0),
                "status" to TextUtil.mm(boardString("all", "tracking.${if (status != null) "selected" else "hint"}",
                    if (status != null) "Показываем количество в инвентаре" else "Одна цель сбора; место и цена не резервируются")),
                "action" to TextUtil.mm(boardString("all", "tracking.$textKey", when (textKey) {
                    "stop" -> "<yellow>[▶] ЛКМ — перестать отслеживать"
                    "start" -> "<green>[▶] ЛКМ — отслеживать"
                    else -> "<gray>Отслеживание сейчас недоступно"
                })),
            ), flags = if (status != null) setOf("tracked") else emptySet(),
        )), enabled = status != null || canTrack) { clicker ->
            val active = tasks ?: return@entry
            if (runtime == null) return@entry
            val change = if (runtime.status(clicker, view) != null) runtime.clear(clicker)
                else if (target != null) runCatching { runtime.toggle(clicker, view, target) }.getOrElse {
                    clicker.sendActionBar(message("all", "tracking.failure", "<yellow>Не удалось сохранить цель. Попробуйте ещё раз."))
                    return@entry
                } else return@entry
            val inventory = clicker.openInventory.topInventory
            change.whenCompleteSync(active) { _, failure ->
                if (!clicker.isOnline) return@whenCompleteSync
                if (failure != null) clicker.sendActionBar(message("all", "tracking.failure", "<yellow>Не удалось сохранить цель. Попробуйте ещё раз."))
                else if (clicker.openInventory.topInventory === inventory) openDetail(clicker, browseGroup, view.contract.id, selection.selected)
            }
        }
    }

    /** Browsing never grants permission to hand in items at an NPC desk. */
    fun openList(player: Player, group: String = "all") {
        if (!groupPattern.matches(group)) {
            player.sendActionBar(message("all", "messages.invalid-group", "<red>Эта книга заказов настроена неверно."))
            return
        }
        openList(player, group, ContractRankPolicyResolver.resolve(player))
    }

    fun openDetail(player: Player, group: String, contractId: String, requestedQuantity: Int? = null) {
        openDetail(player, group, contractId, requestedQuantity, ContractRankPolicyResolver.resolve(player))
    }

    private fun openList(player: Player, group: String, policy: ContractRankPolicy, requestedPage: Int = 0) {
        val views = ContractsManager.currentPlayerViews(player.uniqueId, group.takeUnless { it == "all" }, policy = policy)
            .filter { it.contract.status != ContractStatus.EXPIRED.label }
        val menu = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACTS_LIST)
        val capacity = menu.region(ArcMenuSchema.CONTRACT_ORDERS).size
        val now = System.currentTimeMillis()
        val sorted = views.map { view ->
            val available = PaperContractItems.countPlain(player, view.contract.itemKey)
            val selection = ContractQuantitySelector.select(view, available)
            val originAllowed = ContractOriginGate.canSubmit(player, view.contract.group)
            val availability = ContractBookAvailability.resolve(
                view, available, originAllowed,
                quoteAvailable = selection.canSubmit && (!originAllowed || ContractsManager.quote(player, view.contract.id, selection.selected) != null),
                now = now,
            )
            Triple(view, available, availability)
        }.sortedBy { (_, _, availability) -> when (availability) {
            ContractBookAvailability.READY -> 0
            ContractBookAvailability.ORIGIN_REQUIRED -> 1
            else -> 2
        } }
        val pages = ((sorted.size + capacity - 1) / capacity).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val orders = sorted.drop(page * capacity).take(capacity)
            .map { (view, available, availability) -> orderEntry(group, view, available, availability, now) }
        val elements = buildMap {
            put("info", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_LIST, "info", render(
                "heading" to boardString(group, "list.heading", "<gold><bold>Книга заказов"),
                "description-one" to boardString(group, "list.description-1", "<gray>Выберите, что хотите собрать и сдать."),
                "description-two" to boardString(group, "list.description-2", "<gray>Условия доступны в карточке заказа."),
            ))))
            if (orders.isEmpty() && menu.elements.containsKey(MenuElementId.of("empty"))) put("empty", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_LIST, "empty",
                render("empty" to boardString(group, "list.empty-lore", "<gray>Новые заказы появятся позже.")))))
            listOf("all" to "all", "forge" to "forge_orders", "bank" to "bank_orders", "guild" to "guild_orders").forEach { (tab, target) ->
                val element = "tab-$tab"
                if (menu.elements.containsKey(MenuElementId.of(element))) put(element, ArcMenus.entry(
                    ArcMenus.item(ArcMenuSchema.CONTRACTS_LIST, element, PaperMenuItemRenderContext(
                        values = mapOf("label" to Component.text(groupName(target))),
                        flags = if (group == target) setOf("selected") else emptySet(),
                    )), enabled = group != target,
                ) { openList(it, target) })
            }
            if (menu.elements.containsKey(MenuElementId.of("refresh"))) put("refresh", ArcMenus.entry(
                ArcMenus.item(ArcMenuSchema.CONTRACTS_LIST, "refresh"),
            ) { openList(it, group, ContractRankPolicyResolver.resolve(it), page) })
            listOf("previous" to page - 1, "next" to page + 1).forEach { (element, destination) ->
                if (menu.elements.containsKey(MenuElementId.of(element))) put(element, ArcMenus.entry(
                    ArcMenus.item(ArcMenuSchema.CONTRACTS_LIST, element, PaperMenuItemRenderContext(
                        values = mapOf("page" to Component.text(page + 1), "pages" to Component.text(pages),
                            "label" to TextUtil.mm(boardString("all", "pagination.$element", if (element == "previous") "Назад" else "Дальше"))),
                        flags = if (destination in 0 until pages) setOf("available") else emptySet(),
                    )), enabled = destination in 0 until pages,
                ) { openList(it, group, ContractRankPolicyResolver.resolve(it), destination) })
            }
        }
        ArcMenus.open(player, ArcMenuSchema.CONTRACTS_LIST,
            TextUtil.mm(boardString(group, "list.title", "<dark_gray>Книга заказов"), true),
            elements = elements, regions = mapOf(ArcMenuSchema.CONTRACT_ORDERS to orders))
    }

    private fun orderEntry(group: String, view: ResourceContractPlayerView, available: Int,
                           availability: ContractBookAvailability, now: Long): PaperMenuEntry {
        val selection = ContractQuantitySelector.select(view, available)
        val item = ArcMenus.item("contracts-order", render(
            "contract-name" to view.contract.displayName,
            "available" to available.toString(), "remaining" to view.contract.remainingQuantity.toString(),
            "target" to view.contract.targetQuantity.toString(), "player-remaining" to view.playerRemainingQuantity.toString(),
            "payout" to formatContractMoney(view.playerPayoutMinorPerUnit),
            "cap-bonus" to ((view.capBasisPoints / 100) - 100).toString(),
            "payout-bonus" to ((view.payoutBasisPoints / 100) - 100).toString(),
            "ends-at" to formatTime(view.contract.windowEndsAt),
            "action" to orderStatus(view.contract.group, view, availability, now),
            "can-submit-quantity" to selection.selected.toString(),
            "batch-payout" to if (selection.canSubmit) formatContractMoney(selection.payoutMinor) else "—",
            "personal-accepted" to view.playerAcceptedQuantity.toString(),
            "group-name" to groupName(view.contract.group),
        )).withType(PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER)
        return ArcMenus.entry(item) { openDetail(it, group, view.contract.id) }
    }

    private fun openDetail(player: Player, browseGroup: String, contractId: String, requestedQuantity: Int?,
                           policy: ContractRankPolicy, result: Component = Component.empty()) {
        val views = ContractsManager.currentPlayerViews(player.uniqueId, policy = policy)
        val view = views.firstOrNull { it.contract.id == contractId } ?: return openList(player, browseGroup, policy)
        val group = view.contract.group
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selection = ContractQuantitySelector.select(view, available, requestedQuantity)
        val material = PaperContractItems.material(view.contract.itemKey) ?: Material.PAPER
        val quote = ContractsManager.quote(player, contractId, selection.selected)
        val originAllowed = ContractOriginGate.canSubmit(player, group)
        val availability = ContractBookAvailability.resolve(view, available, originAllowed, quote != null)
        val canSubmit = availability == ContractBookAvailability.READY
        val menu = ArcMenus.current().catalog.require(ArcMenuSchema.CONTRACTS_DETAIL)
        val nextOrder = views.filter { it.contract.id != contractId }.sortedBy { it.contract.group != group }.firstOrNull {
            val count = PaperContractItems.countPlain(player, it.contract.itemKey)
            ContractBookAvailability.resolve(it, count, true) == ContractBookAvailability.READY
        }
        val elements = buildMap {
            put("resource", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "resource", render(
                "contract-name" to view.contract.displayName, "available" to available.toString(),
                "remaining" to view.contract.remainingQuantity.toString(), "player-remaining" to view.playerRemainingQuantity.toString(),
                "cap-bonus" to ((view.capBasisPoints / 100) - 100).toString(),
            )).withType(material)))
            put("info", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "info", PaperMenuItemRenderContext(
                values = mapOf(
                    "heading" to TextUtil.mm(boardString(group, "detail.info-heading", "<gold><bold>Общий заказ")),
                    "accepted" to Component.text(view.contract.acceptedQuantity), "target" to Component.text(view.contract.targetQuantity),
                    "contributors" to Component.text(view.contract.contributors),
                    "personal-accepted" to Component.text(view.playerAcceptedQuantity),
                    "purpose" to TextUtil.mm(boardString(group, "detail.purpose", "<gray>Ресурсы для поселения.")),
                    "result" to result, "can-submit-quantity" to Component.text(selection.selected),
                ), flags = if (result != Component.empty()) setOf("has-result") else emptySet(),
            ))))
            put("payout", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "payout", render(
                "payout" to (quote?.payoutMinor ?: selection.payoutMinor).let { if (selection.canSubmit) formatContractMoney(it) else "—" },
                "per-unit" to formatContractMoney(view.playerPayoutMinorPerUnit),
                "payout-bonus" to ((view.payoutBasisPoints / 100) - 100).toString(),
            ))))
            put("quantity", ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "quantity", render(
                "selected" to selection.selected.toString(), "minimum" to selection.minimum.toString(), "maximum" to selection.maximum.toString(),
            )).withType(material)) { context ->
                val event = context.event
                if ((event.isLeftClick || event.isRightClick) && selection.canSubmit) openDetail(
                    context.player, browseGroup, contractId,
                    ContractQuantitySelector.adjust(selection, event.isRightClick, event.isShiftClick),
                )
            })
            listOf("min" to selection.minimum, "stack" to material.maxStackSize, "max" to selection.maximum).forEach { (preset, requested) ->
                val element = "quantity-$preset"
                if (menu.elements.containsKey(MenuElementId.of(element))) {
                    val presetAvailable = selection.canSubmit && (preset != "stack" || material.maxStackSize >= selection.minimum)
                    val quantity = if (presetAvailable) requested.coerceIn(selection.minimum, selection.maximum) else 0
                    put(element, ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, element, PaperMenuItemRenderContext(
                        values = mapOf("label" to TextUtil.mm(boardString("all", "quantity.$preset", preset)), "quantity" to Component.text(quantity)),
                        flags = if (presetAvailable) setOf("available") else emptySet(),
                    )), enabled = presetAvailable) { openDetail(it, browseGroup, contractId, quantity) })
                }
            }
            if (menu.elements.containsKey(MenuElementId.of("track"))) put("track", trackingEntry(player, view, selection, browseGroup))
            if (menu.elements.containsKey(MenuElementId.of("next-order"))) put("next-order", ArcMenus.entry(
                ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "next-order", PaperMenuItemRenderContext(
                    values = mapOf("contract-name" to Component.text(nextOrder?.contract?.displayName ?: "—")),
                    flags = if (nextOrder != null) setOf("available") else emptySet(),
                )), enabled = nextOrder != null,
            ) { if (nextOrder != null) openDetail(it, browseGroup, nextOrder.contract.id) })
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "back")) { openList(it, browseGroup) })
            put("confirm", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.CONTRACTS_DETAIL, "confirm", PaperMenuItemRenderContext(
                values = mapOf("selected" to Component.text(selection.selected),
                    "payout" to Component.text(quote?.payoutMinor?.let(::formatContractMoney) ?: "—"),
                    "unavailable-reason" to TextUtil.mm(availabilityText(group, availability))),
                flags = buildSet { if (canSubmit) add("can-submit"); if (originAllowed) add("origin-allowed"); if (quote != null) add("quote-available") },
            )), enabled = canSubmit) { submit(it, group, quote, browseGroup) })
        }
        ArcMenus.open(player, ArcMenuSchema.CONTRACTS_DETAIL,
            TextUtil.mm(boardString(group, "detail.title", "<dark_gray>Сдать ресурсы"), true), elements = elements)
    }

    private fun groupName(group: String): String = boardString(group, "name", when (group) {
        "all" -> "Все заказы"
        "forge_orders" -> "Кузница"
        "bank_orders" -> "Банк"
        "guild_orders" -> "Гильдия"
        else -> "Заказы"
    })

    private fun render(vararg values: Pair<String, String>) = PaperMenuItemRenderContext(
        values = values.associate { (key, value) -> key to TextUtil.mm(value, true) },
    )

    private fun submit(
        player: Player,
        group: String,
        quote: ContractSubmissionQuote?,
        browseGroup: String = group,
    ) {
        if (!ContractOriginGate.canSubmit(player, group)) {
            player.sendActionBar(message(group, "messages.origin-required", "<yellow>Сдать заказ можно только у конторщика на спавне."))
            return
        }
        if (quote == null) {
            player.sendActionBar(message(group, "messages.unavailable", "<yellow>Этот заказ больше недоступен. Обновите книгу заказов."))
            return
        }
        val active = tasks ?: return
        player.closeInventory()
        player.sendActionBar(message(group, "messages.processing", "<gray>Проверяем ресурсы и запись в книге…"))
        ContractsManager.submit(player, quote).whenCompleteSync(active) { outcome, failure ->
            if (!player.isOnline) return@whenCompleteSync
            if (failure != null || outcome == null) {
                player.sendMessage(
                    message(
                        group,
                        "messages.failure",
                        "<red>Заказ остановлен для проверки. <gray>Предметы повторно не сдавайте.",
                    ),
                )
                return@whenCompleteSync
            }
            player.sendMessage(ContractPlayerMessages.render(outcome, contractGuiConfig, group))
            // A delayed payment result must not replace a different menu the player opened.
            if (player.openInventory.topInventory.type != InventoryType.CRAFTING) return@whenCompleteSync
            if (outcome is ContractSubmissionOutcome.Committed) {
                openDetail(player, browseGroup, quote.contractId, null, ContractRankPolicyResolver.resolve(player),
                    TextUtil.mm(boardString(group, "messages.receipt", "<green>Сдано {quantity} · Получено {payout} <white>💰</white>")
                        .replace("{quantity}", outcome.receipt.quantity.toString())
                        .replace("{payout}", formatContractMoney(outcome.receipt.payoutMinor))))
            } else if (outcome !is ContractSubmissionOutcome.ManualReview) openList(player, browseGroup)
        }
    }

    private fun orderStatus(
        group: String,
        view: ResourceContractPlayerView,
        availability: ContractBookAvailability,
        now: Long,
    ): String {
        val status = availabilityText(group, availability)
        val next = ContractBookAvailability.nextOpeningAt(view, now)
        val schedule = next?.let {
            val key = if (now < view.contract.windowStartsAt) "opens-at" else "renews-at"
            val fallback = if (key == "opens-at") "<gray>Начало приёма: <white>{time}" else "<gray>Книга обновится: <white>{time}"
            boardString(group, "availability.$key", fallback).replace("{time}", formatTime(it))
        }
        return listOfNotNull(schedule, status).joinToString(if (availability == ContractBookAvailability.READY) "\n\n" else "\n")
    }

    private fun availabilityText(group: String, availability: ContractBookAvailability): String =
        boardString(
            group,
            "availability.${availability.messageKey}",
            boardString(group, "messages.${availability.messageKey}", availability.fallback),
        )

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
