package ru.arc.enchanting

import net.advancedplugins.ae.api.AEAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.contracts.formatContractMoney
import ru.arc.core.LifecycleTaskScope
import ru.arc.eliteloot.isAdvancedEnchantmentsBook
import ru.arc.gui.ArcMenus
import ru.arc.helpcenter.HelpCenterEnchantment
import ru.arc.helpcenter.HelpCenterEnchantmentsCatalog
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.payments.FileItemPaymentJournal
import ru.arc.payments.ItemPaymentOutcome
import ru.arc.payments.ItemPaymentRequest
import ru.arc.payments.ItemPayments
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

internal data class AdvancedBookLevelOffer(val level: Int, val priceMinor: Long)

/** Pure offer filtering: only provider-configured levels and public, enchanter-enabled groups. */
internal fun advancedBookLevelOffers(
    enchantment: HelpCenterEnchantment,
    configuredLevels: List<Int>,
    priceMinor: (group: String, level: Int) -> Long?,
): List<AdvancedBookLevelOffer> {
    val group = enchantment.group.trim().uppercase(Locale.ROOT)
    if (!enchantment.availableFromEnchanter || group !in AdvancedBookShop.PUBLIC_GROUPS) return emptyList()
    return configuredLevels.asSequence()
        .filter { it > 0 }
        .distinct()
        .sorted()
        .mapNotNull { level ->
            val price = priceMinor(group, level)?.takeIf { it > 0L } ?: return@mapNotNull null
            AdvancedBookLevelOffer(level, price)
        }
        .toList()
}

/** Native AE book seller with a durable Vault debit and exact-slot replacement. */
internal class AdvancedBookShop(
    private val config: EnchantingConfig,
    private val payments: ItemPayments = productionPayments(),
    private val catalog: () -> HelpCenterEnchantmentsCatalog = HelpCenterEnchantmentsCatalog::fromAeApi,
    private val configuredLevels: (String) -> List<Int> = ::configuredAdvancedBookLevels,
    private val isAvailableFromEnchanter: (String) -> Boolean = ::isAdvancedBookAvailableFromEnchanter,
    private val createBook: (String, Int, Player) -> ItemStack = ::createFreshAdvancedBook,
    private val serverName: () -> String = { ARC.serverName.orEmpty() },
    private val taskScope: LifecycleTaskScope = LifecycleTaskScope(),
    private val openDialog: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit) -> Unit =
        { player, screen, reopen, onDismiss -> ArcMenus.openDialog(player, screen, reopen = reopen, onDismiss = onDismiss) },
    private val beginDialogFlow: (Player) -> Unit = ArcMenus::beginDialogFlow,
) : AutoCloseable {
    private enum class Stage { SELECTING, CONFIRMING, PAYMENT, RESULT, RETIRED }

    private data class Flow(
        val playerId: UUID,
        val enchantment: HelpCenterEnchantment,
        val group: String,
        val offers: List<AdvancedBookLevelOffer>,
        val returnTo: () -> Unit,
        var stage: Stage = Stage.SELECTING,
        var selected: AdvancedBookLevelOffer? = null,
        var paymentOperationId: UUID? = null,
        var dismissedBeforeResult: Boolean = false,
        var transitioningToResult: Boolean = false,
    )

    private data class ResultView(
        val key: String,
        val returnTo: () -> Unit,
        val enchantment: HelpCenterEnchantment,
        val level: Int,
        val priceMinor: Long,
        val chances: BookApplicationChances? = null,
    )

    private val flows = mutableMapOf<UUID, Flow>()
    private var active = true

    fun start() {
        if (active) payments.start()
    }

    fun canPurchase(player: Player, enchantmentId: String): Boolean {
        if (!active || !player.isOnline || !atShop(player) ||
            !Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")
        ) return false

        val group = runCatching { AEAPI.getGroup(enchantmentId).orEmpty().uppercase(Locale.ROOT) }.getOrDefault("")
        if (group !in PUBLIC_GROUPS || !runCatching { isAvailableFromEnchanter(enchantmentId) }.getOrDefault(false)) return false
        val levels = runCatching { configuredLevels(enchantmentId) }.getOrDefault(emptyList())
        return levels.any { config.shopPriceMinor(group, it) != null }
    }

    /** Opens a child of the caller's native dialog flow; it deliberately does not reset shared history. */
    fun openPurchase(player: Player, enchantmentId: String, returnTo: () -> Unit): Boolean {
        if (!canPurchase(player, enchantmentId)) {
            if (player.isOnline && !atShop(player)) player.sendMessage(config.text("shop.messages.spawn-only"))
            return false
        }
        val snapshot = runCatching(catalog).getOrNull()?.takeIf { it.available } ?: run {
            player.sendMessage(config.text("shop.result.failed"))
            return false
        }
        val enchantment = snapshot.entries.firstOrNull { it.id.equals(enchantmentId, ignoreCase = true) }
            ?.takeIf { it.availableFromEnchanter && it.group.trim().uppercase(Locale.ROOT) in PUBLIC_GROUPS }
            ?: return false
        val group = enchantment.group.trim().uppercase(Locale.ROOT)
        val levels = runCatching { configuredLevels(enchantment.id) }.getOrDefault(emptyList())
        val offers = advancedBookLevelOffers(enchantment, levels, config::shopPriceMinor)
        if (offers.isEmpty()) return false

        flows.remove(player.uniqueId)?.let(::retire)
        val flow = Flow(player.uniqueId, enchantment, group, offers, returnTo)
        flows[player.uniqueId] = flow
        showSelection(player, flow, 0)
        return true
    }

    override fun close() {
        if (!active) return
        active = false
        flows.values.toList().forEach(::retire)
        flows.clear()
        taskScope.close()
        payments.close()
    }

    private fun showSelection(player: Player, flow: Flow, requestedPage: Int) {
        if (!owns(flow, Stage.SELECTING) || !player.isOnline) return
        if (!atShop(player)) {
            returnToCaller(player, flow, "shop.messages.spawn-only")
            return
        }
        val pageCount = ((flow.offers.size + LEVELS_PER_PAGE - 1) / LEVELS_PER_PAGE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val pageOffers = flow.offers.drop(page * LEVELS_PER_PAGE).take(LEVELS_PER_PAGE)
        val body = listOf(
            PaperDialogBody(config.text("shop.select-body"), WIDTH),
            DialogTables.body(
                rows = listOf(
                    config.text("shop.table.enchantment") to Component.text(flow.enchantment.name),
                    config.text("shop.table.group") to config.text("shop.groups.${flow.group}"),
                ),
                frame = DialogTables.Frame.LEGENDARY,
                width = WIDTH,
                columns = DialogTables.Columns.VALUE_WIDE,
            ),
        )
        val buttons = buildList {
            pageOffers.forEachIndexed { index, offer ->
                add(
                    PaperDialogButton(
                        id = PaperDialogActionId.of("level_${page * LEVELS_PER_PAGE + index}"),
                        label = config.text("shop.level.label", Placeholder.unparsed("level", offer.level.toString())),
                        tooltip = config.text(
                            "shop.level.tooltip",
                            Placeholder.unparsed("price", formatContractMoney(offer.priceMinor)),
                        ),
                        width = LEVEL_BUTTON_WIDTH,
                        onClick = { context -> selectLevel(context.player, flow, offer) },
                    ),
                )
            }
            if (page > 0) {
                add(
                    PaperDialogButton(
                        PaperDialogActionId.of("previous_levels"),
                        config.text("shop.level.previous"),
                        width = LEVEL_BUTTON_WIDTH,
                        onClick = { showSelection(player, flow, page - 1) },
                    ),
                )
            }
            if (page + 1 < pageCount) {
                add(
                    PaperDialogButton(
                        PaperDialogActionId.of("next_levels"),
                        config.text("shop.level.next"),
                        width = LEVEL_BUTTON_WIDTH,
                        onClick = { showSelection(player, flow, page + 1) },
                    ),
                )
            }
            add(
                PaperDialogButton(
                    PaperDialogActionId.of("return_to_caller"),
                    config.text("shop.return.label"),
                    width = WIDTH,
                    closeDialogBeforeAction = true,
                    onClick = { returnToCaller(player, flow) },
                ),
            )
        }
        openDialog(
            player,
            PaperDialogScreen(
                id = SELECT_SCREEN,
                title = config.text("shop.title"),
                body = body,
                buttons = buttons,
                columns = 2,
            ),
            { showSelection(player, flow, page) },
            { retire(flow) },
        )
    }

    private fun selectLevel(player: Player, flow: Flow, offer: AdvancedBookLevelOffer) {
        if (!owns(flow, Stage.SELECTING) || !isCurrentOffer(flow, offer)) {
            player.sendMessage(config.text("shop.result.stale"))
            return
        }
        flow.selected = offer
        flow.stage = Stage.CONFIRMING
        showConfirmation(player, flow)
    }

    private fun showConfirmation(player: Player, flow: Flow) {
        val selected = flow.selected ?: return
        if (!owns(flow, Stage.CONFIRMING) || !player.isOnline) return
        if (!atShop(player)) {
            returnToCaller(player, flow, "shop.messages.spawn-only")
            return
        }
        val price = priceComponent(selected.priceMinor)
        val screen = PaperDialogScreen(
            id = CONFIRM_SCREEN,
            title = config.text("shop.title"),
            body = listOf(
                PaperDialogBody(config.text("shop.confirm-body"), WIDTH),
                PaperDialogBody(config.text("shop.chances"), WIDTH),
                DialogTables.body(
                    rows = listOf(
                        config.text("shop.table.enchantment") to Component.text(flow.enchantment.name),
                        config.text("shop.table.group") to config.text("shop.groups.${flow.group}"),
                        config.text("shop.table.level") to Component.text(selected.level),
                        config.text("shop.table.price") to price,
                    ),
                    frame = DialogTables.Frame.LEGENDARY,
                    width = WIDTH,
                    columns = DialogTables.Columns.VALUE_WIDE,
                ),
            ),
            buttons = listOf(
                PaperDialogButton(
                    PaperDialogActionId.of("buy_book"),
                    config.text("shop.buy.label", Placeholder.unparsed("price", formatContractMoney(selected.priceMinor))),
                    config.text("shop.buy.tooltip"),
                    width = WIDTH,
                    onClick = { context -> submitPurchase(context.player, flow, selected) },
                ),
                PaperDialogButton(
                    PaperDialogActionId.of("back_to_levels"),
                    config.text("shop.back.label"),
                    width = WIDTH,
                    onClick = { context -> backToLevels(context.player, flow) },
                ),
            ),
            columns = 1,
        )
        openDialog(
            player,
            screen,
            { showConfirmation(player, flow) },
            {
                if (flow.stage == Stage.CONFIRMING) {
                    flow.stage = Stage.SELECTING
                    flow.selected = null
                }
            },
        )
    }

    private fun backToLevels(player: Player, flow: Flow) {
        if (!owns(flow, Stage.CONFIRMING)) return
        flow.stage = Stage.SELECTING
        flow.selected = null
        showSelection(player, flow, 0)
    }

    private fun submitPurchase(player: Player, flow: Flow, selected: AdvancedBookLevelOffer) {
        if (!owns(flow, Stage.CONFIRMING) || flow.selected != selected || !player.isOnline) return
        if (flow.paymentOperationId != null) {
            player.sendMessage(config.text("shop.processing-body"))
            return
        }
        if (!isCurrentOffer(flow, selected)) {
            player.sendMessage(config.text("shop.result.stale"))
            backToLevels(player, flow)
            return
        }
        if (payments.isReady.not()) {
            player.sendMessage(config.text("shop.result.failed"))
            return
        }
        val slot = (0..35).firstOrNull { isEmpty(player.inventory.getItem(it)) }
        if (slot == null) {
            player.sendMessage(config.text("shop.result.no-space"))
            return
        }
        val book = runCatching { createBook(flow.enchantment.id, selected.level, player) }.getOrElse {
            player.sendMessage(config.text("shop.result.failed"))
            return
        }
        val chances = runCatching {
            check(book.amount == 1 && isAdvancedEnchantmentsBook(book) && isAdvancedBookRiskSafe(book))
            advancedBookChances(book)
        }.getOrElse {
            player.sendMessage(config.text("shop.result.failed"))
            return
        }
        val replacementBytes = book.serializeAsBytes()
        // Bukkit's ItemStack byte codec rejects AIR. ItemPayments persists (but never
        // deserializes) the original snapshot; use an explicit versioned empty-slot marker.
        val emptySlotSnapshot = EMPTY_SLOT_SNAPSHOT.copyOf()
        val operationId = UUID.randomUUID()
        flow.stage = Stage.PAYMENT
        flow.paymentOperationId = operationId
        showProcessing(player, flow, operationId)
        payments.submit(
            ItemPaymentRequest(
                operationId = operationId,
                playerId = flow.playerId,
                slot = slot,
                originalItemBytes = emptySlotSnapshot,
                replacementItemBytes = replacementBytes,
                priceMinor = selected.priceMinor,
            ),
            validate = {
                ownsPayment(flow, operationId) && player.isOnline && atShop(player) &&
                    currentOfferStillValid(flow, selected) && isEmpty(player.inventory.getItem(slot))
            },
            apply = {
                player.inventory.setItem(slot, book.clone())
                check(player.inventory.getItem(slot)?.serializeAsBytes()?.contentEquals(replacementBytes) == true) {
                    "Advanced book payment succeeded but the reserved slot did not retain its exact item"
                }
            },
            completion = { outcome -> completePayment(player, flow, selected, chances, operationId, outcome) },
        )
    }

    private fun showProcessing(player: Player, flow: Flow, operationId: UUID) {
        openDialog(
            player,
            PaperDialogScreen(
                id = PROCESSING_SCREEN,
                title = config.text("shop.processing-title"),
                body = listOf(PaperDialogBody(config.text("shop.processing-body"), WIDTH)),
                buttons = emptyList(),
                columns = 1,
            ),
            { if (ownsPayment(flow, operationId)) showProcessing(player, flow, operationId) },
            {
                if (ownsPayment(flow, operationId)) {
                    // This causes ItemPayments' post-journal validation to cancel before Vault withdrawal.
                    flow.stage = Stage.CONFIRMING
                }
            },
        )
    }

    private fun completePayment(
        player: Player,
        flow: Flow,
        selected: AdvancedBookLevelOffer,
        chances: BookApplicationChances,
        operationId: UUID,
        outcome: ItemPaymentOutcome,
    ) {
        if (flow.paymentOperationId != operationId) return
        if (flow.stage == Stage.CONFIRMING && outcome == ItemPaymentOutcome.CANCELLED) {
            // The player backed out while the durable intent was settling. Keep the
            // confirm screen usable only after ItemPayments releases its player lock.
            flow.paymentOperationId = null
            return
        }
        if (!ownsPayment(flow, operationId)) return
        val key = when (outcome) {
            ItemPaymentOutcome.SUCCESS -> "shop.result.success"
            ItemPaymentOutcome.INSUFFICIENT_FUNDS -> "shop.result.insufficient"
            ItemPaymentOutcome.CANCELLED -> "shop.result.stale"
            ItemPaymentOutcome.BUSY_OR_DUPLICATE, ItemPaymentOutcome.UNAVAILABLE, ItemPaymentOutcome.FAILED_UNCHARGED -> "shop.result.failed"
            ItemPaymentOutcome.OUTCOME_UNKNOWN -> "shop.result.unknown"
        }
        flow.stage = Stage.RESULT
        flow.paymentOperationId = null
        val result = ResultView(key, flow.returnTo, flow.enchantment, selected.level, selected.priceMinor, chances.takeIf { outcome == ItemPaymentOutcome.SUCCESS })
        taskScope.runLater(1) {
            if (!active || !player.isOnline || flow.stage != Stage.RESULT || flow.dismissedBeforeResult || flows[flow.playerId] !== flow) return@runLater
            // Leave no stale confirmation behind the terminal result after an async payment.
            flow.transitioningToResult = true
            flows.remove(flow.playerId, flow)
            beginDialogFlow(player)
            openResult(player, result)
        }
    }

    private fun openResult(player: Player, result: ResultView) {
        val chanceTags = result.chances?.let { chances ->
            listOf(
                Placeholder.unparsed("success", chances.success.toString()),
                Placeholder.unparsed("failure", chances.destroyOnFailure.toString()),
            )
        }.orEmpty()
        val resolvers = chanceTags + listOf(
            Placeholder.unparsed("name", result.enchantment.name),
            Placeholder.unparsed("level", result.level.toString()),
            Placeholder.unparsed("price", formatContractMoney(result.priceMinor)),
        )
        openDialog(
            player,
            PaperDialogScreen(
                id = RESULT_SCREEN,
                title = config.text("shop.result-title"),
                body = listOf(PaperDialogBody(config.text(result.key, *resolvers.toTypedArray()), WIDTH)),
                buttons = listOf(
                    PaperDialogButton(
                        PaperDialogActionId.of("return_from_result"),
                        config.text("shop.return.label"),
                        width = WIDTH,
                        closeDialogBeforeAction = true,
                        onClick = { result.returnTo() },
                    ),
                ),
                columns = 1,
            ),
            { if (player.isOnline) openResult(player, result) },
            {},
        )
    }

    private fun currentOfferStillValid(flow: Flow, selected: AdvancedBookLevelOffer): Boolean {
        if (!active || !Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") ||
            !flow.enchantment.availableFromEnchanter ||
            !runCatching { isAvailableFromEnchanter(flow.enchantment.id) }.getOrDefault(false) ||
            flow.group !in PUBLIC_GROUPS
        ) return false
        val currentGroup = runCatching { AEAPI.getGroup(flow.enchantment.id).orEmpty().uppercase(Locale.ROOT) }
            .getOrDefault("")
        if (currentGroup != flow.group) return false
        val levels = runCatching { configuredLevels(flow.enchantment.id) }.getOrDefault(emptyList())
        return selected in flow.offers && selected.level in levels &&
            config.shopPriceMinor(flow.group, selected.level) == selected.priceMinor
    }

    private fun isCurrentOffer(flow: Flow, offer: AdvancedBookLevelOffer): Boolean {
        val player = Bukkit.getPlayer(flow.playerId) ?: return false
        return active && flows[flow.playerId] === flow && player.isOnline && atShop(player) &&
            currentOfferStillValid(flow, offer)
    }

    private fun owns(flow: Flow, stage: Stage): Boolean =
        active && flows[flow.playerId] === flow && flow.stage == stage

    private fun ownsPayment(flow: Flow, operationId: UUID): Boolean =
        owns(flow, Stage.PAYMENT) && flow.paymentOperationId == operationId

    private fun atShop(player: Player): Boolean =
        serverName() == config.shopServerName && player.world.name == config.shopWorldName

    private fun returnToCaller(player: Player, flow: Flow, messageKey: String? = null) {
        messageKey?.let { player.sendMessage(config.text(it)) }
        retire(flow)
        flow.returnTo()
    }

    private fun retire(flow: Flow) {
        if (flow.stage == Stage.RETIRED) return
        if (flow.stage == Stage.RESULT && !flow.transitioningToResult) flow.dismissedBeforeResult = true
        flow.stage = Stage.RETIRED
        flow.paymentOperationId = null
        flows.remove(flow.playerId, flow)
    }

    private fun priceComponent(priceMinor: Long): Component =
        Component.text(formatContractMoney(priceMinor))
            .append(Component.space())
            .append(Component.text("💰", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            .decoration(TextDecoration.ITALIC, false)

    companion object {
        internal val PUBLIC_GROUPS = setOf("SIMPLE", "UNIQUE", "ELITE", "ULTIMATE", "LEGENDARY", "FABLED")

        private const val WIDTH = 300
        private const val LEVELS_PER_PAGE = 10
        private const val LEVEL_BUTTON_WIDTH = 145
        private const val SELECT_SCREEN = "enchanting.book.shop.select"
        private const val CONFIRM_SCREEN = "enchanting.book.shop.confirm"
        private const val PROCESSING_SCREEN = "enchanting.book.shop.processing"
        private const val RESULT_SCREEN = "enchanting.book.shop.result"
        private val EMPTY_SLOT_SNAPSHOT = "arc:empty-inventory-slot:v1".toByteArray(Charsets.UTF_8)

        private fun productionPayments(): ItemPayments = ItemPayments(
            journalFactory = {
                FileItemPaymentJournal(
                    ARC.instance.dataPath,
                    Path.of("data", "enchanting", "purchases"),
                )
            },
        )

        private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type.isAir
    }
}
