package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperCloudStorageButton
import ru.arc.paper.menu.PaperCloudStorageContent
import ru.arc.paper.menu.PaperCloudStorageSession
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode

/** Common NPC desk: native cloud-chest input, explicit sale, durable contract escrow. */
object NpcContractDepositGui : Listener {
    private val sessions = mutableMapOf<UUID, Desk>()
    private val processing = mutableSetOf<UUID>()
    private var tasks: LifecycleTaskScope? = null
    private val config get() = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/contracts.yml")
    private class Desk(val player: Player, val group: String, val storage: ContractDeskStorage, val rows: Int) {
        lateinit var session: PaperCloudStorageSession
        val menu = ArcMenuSchema.CONTRACT_DESKS.getValue(rows)
        var page = 0
    }
    fun start() {
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
        tasks?.runTimer(100, 100) { sessions.values.toList().forEach(::render) }
    }
    fun shutdown() {
        sessions.values.toList().forEach { it.session.close() }
        tasks?.close()
        tasks = null
        HandlerList.unregisterAll(this)
        sessions.clear()
        processing.clear()
    }
    fun open(player: Player, group: String) {
        if (!ContractOriginGate.canSubmit(player, group)) return
        if (player.uniqueId in processing) { feedback(player, "processing", "<#ff9f0f>Принимаем товары…"); return }
        sessions[player.uniqueId]?.takeIf { it.group == group && it.session.isOpen }?.let { render(it); return }
        val orders = views(player, group)
        val geometry = ContractDeskLayout.calculate(orders.size)
        val storage = ContractDeskStorage(player, group, { offered -> matchingOrders(offered, views(player, group)).isNotEmpty() })
        val desk = Desk(player, group, storage, geometry.rows)
        ArcMenus.closeDialog(player)
        desk.session = ArcMenus.openStorage(player, desk.menu, ArcMenuSchema.CONTRACT_DEPOSIT, storage,
            PaperCloudStorageContent(
                title = TextUtil.mm(config.string("boards.$group.desk-title", "<#20252b>Заказы")),
                background = ArcMenus.background(desk.menu),
                buttons = mapOf(
                    MenuElementId.of("sell") to PaperCloudStorageButton(ArcMenus.item(desk.menu, "sell", context("status" to Component.empty(), "page" to Component.empty()))) { sell(desk) },
                    MenuElementId.of("previous") to PaperCloudStorageButton(ArcMenus.item(desk.menu, "previous")) { turnPage(desk, -1) },
                    MenuElementId.of("next") to PaperCloudStorageButton(ArcMenus.item(desk.menu, "next")) { turnPage(desk, 1) },
                ),
                onFailure = { viewer, _ ->
                    if (storage.pending) feedback(viewer, "processing", "<#ff9f0f>Принимаем товары…")
                    else feedback(viewer, "transfer-rejected", "<#ff9f0f>Нет места или этот товар не входит в список заказов.")
                },
            ))
        sessions[player.uniqueId] = desk
        render(desk)
    }
    private fun views(player: Player, group: String) = ContractsManager.currentPlayerViews(
        player.uniqueId, group, policy = ContractRankPolicyResolver.resolve(player),
    )
    private fun turnPage(desk: Desk, step: Int) {
        if (desk.storage.pending) return
        desk.page += step
        render(desk)
    }
    private fun render(desk: Desk) {
        if (!desk.session.isOpen) return
        val orders = views(desk.player, desk.group)
        // A live catalog generation closes these sessions on reload. Between
        // weekly rotations retain the open chest size and paginate its capacity.
        val geometry = ContractDeskLayout.calculate(orders.size, desk.page)
        val pages = geometry.pageCount
        desk.page = geometry.page
        val visible = geometry.visibleOrderRange.map(orders::get)
        val layout = ArcMenus.current().catalog.require(desk.menu)
        val inventory = desk.session.inventory
        val empty = ArcMenus.item("contract-desk-empty")
        val leftSlots = layout.region(ArcMenuSchema.CONTRACT_DESK_ORDERS).map { it.index } +
            (layout.slot("previous").index..layout.slot("next").index)
        leftSlots.forEach { inventory.setItem(it, empty.clone()) }
        visible.zip(geometry.orderSlots).forEach { (view, slot) ->
            val material = PaperContractItems.material(view.contract.itemKey) ?: return@forEach
            val definition = view.pricingDefinition ?: return@forEach
            val market = ContractMarketPricing.unitPayoutMinor(definition, view.pricingSupply, view.pricingAt)
            val availability = ContractBookAvailability.resolve(view, view.maxSubmissionQuantity,
                ContractOriginGate.canSubmit(desk.player, desk.group))
            inventory.setItem(slot, ArcMenus.item("contract-desk-order", context(
                "name" to Component.text(view.contract.displayName),
                "accepted" to if (view.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
                    text("fish", "<#e6fff3>Любая сырая рыба: треска, лосось, тропическая, иглобрюх.")
                    else text("plain-item", "<#e6fff3>Принимается обычный предмет без переименования."),
                "price" to Component.text(formatContractMoney(view.playerPayoutMinorPerUnit)),
                "base" to Component.text(formatContractMoney(definition.payoutMinorPerUnit)),
                "growth" to Component.text(contractPriceGrowth(definition.payoutMinorPerUnit, market)),
                "rank" to Component.text(contractPriceGrowth(10_000L, view.payoutBasisPoints.toLong())),
                "remaining" to Component.text(view.playerRemainingQuantity),
                "state" to if (availability == ContractBookAvailability.READY)
                    text("accepting", "<#2bba43>Положите товар в ячейки справа и нажмите «Продать».")
                    else TextUtil.mm(config.string("defaults.availability.${availability.messageKey}", availability.fallback)),
            )).withType(material))
        }
        val status = if (desk.storage.pending) text("processing", "<#ff9f0f>Принимаем товары…")
            else if (orders.isEmpty()) text("empty", "<#ff9f0f>Сейчас открытых заказов нет.")
            else text("instruction", "<#e6fff3>Слева — заказы. Справа — товары для сдачи.")
        inventory.setItem(layout.slot("sell").index, ArcMenus.item(desk.menu, "sell", context(
            "status" to status, "page" to if (pages > 1) Component.text("Страница ${desk.page + 1} / $pages") else Component.empty())))
        inventory.setItem(layout.slot("previous").index, if (pages > 1 && desk.page > 0) ArcMenus.item(desk.menu, "previous") else empty.clone())
        inventory.setItem(layout.slot("next").index, if (pages > 1 && desk.page < pages - 1) ArcMenus.item(desk.menu, "next") else empty.clone())
        desk.session.refresh()
    }

    internal fun matchingOrders(offered: ItemStack, orders: List<ResourceContractPlayerView>) = orders.filter { view ->
        PaperContractItems.material(view.contract.itemKey)?.let { material ->
            PaperContractItems.isPlainExact(offered, material, view.contract.itemKey)
        } == true
    }
    internal fun nextQuote(offered: ItemStack, amount: Int, orders: List<ResourceContractPlayerView>,
        quote: (String, Int) -> ContractSubmissionQuote?): ContractSubmissionQuote? =
        matchingOrders(offered, orders).mapNotNull { view ->
            // A shared network budget may fund a smaller batch than the order's
            // own budget. Never sweep unoffered items to fill that batch.
            quote(view.contract.id, minOf(amount, view.maxSubmissionQuantity))
                ?.let { view to it }
        }.sortedWith { a, b ->
            val price = (b.second.payoutMinor * a.second.quantity).compareTo(a.second.payoutMinor * b.second.quantity)
            if (price != 0) price else (a.first.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
                .compareTo(b.first.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
        }.firstOrNull()?.second

    private fun sell(desk: Desk) {
        if (desk.storage.pending || !desk.session.isOpen) return
        if (!ContractOriginGate.canSubmit(desk.player, desk.group)) {
            feedback(desk.player, "unavailable", "<#ff9f0f>Снова обратитесь к NPC, чтобы сдать товары.")
            return
        }
        desk.storage.pending = true
        processing += desk.player.uniqueId
        render(desk)
        fun finish(accepted: Long, payout: Long, review: Boolean = false) {
            desk.storage.pending = false
            processing -= desk.player.uniqueId
            if (desk.player.isOnline) {
                if (accepted > 0) desk.player.sendMessage(text("success", "<#2bba43>Принято <quantity> шт. · +<price> <white>💰</white>",
                    "quantity" to Component.text(accepted), "price" to Component.text(formatContractMoney(payout))))
                if (review) feedback(desk.player, "review", "<#c42323>Сдача остановлена для проверки. Не повторяйте её до разбора администратором.")
                else if (desk.storage.snapshot().any { it != null }) feedback(desk.player, "not-accepted", "<#ff9f0f>Остаток не принят: проверьте лимиты заказов.")
                if (desk.session.isOpen) render(desk) else returnItems(desk)
            }
        }
        fun submitNext(accepted: Long, payout: Long) {
            val orders = views(desk.player, desk.group)
            val offeredItems = desk.storage.snapshot().filterNotNull()
            val quote = offeredItems.firstNotNullOfOrNull { offered ->
                nextQuote(offered, offeredItems.filter { it.isSimilar(offered) }.sumOf { it.amount }, orders)
                    { id, quantity -> ContractsManager.quote(desk.player, id, quantity) }
            }
            if (quote == null) { finish(accepted, payout); return }
            val view = orders.first { it.contract.id == quote.contractId }
            val prepared = desk.storage.prepare(view.contract.itemKey, quote.quantity)
            if (prepared == null) { finish(accepted, payout); return }
            ContractsManager.submit(desk.player, quote, offeredInventory = prepared).whenCompleteSync(requireNotNull(tasks)) { outcome, failure ->
                if (failure != null || outcome is ContractSubmissionOutcome.ManualReview || outcome is ContractSubmissionOutcome.Unavailable)
                    finish(accepted, payout, review = true)
                else if (outcome is ContractSubmissionOutcome.Committed) {
                    desk.session.refresh()
                    submitNext(accepted + outcome.receipt.quantity, payout + outcome.receipt.payoutMinor)
                } else finish(accepted, payout)
            }
        }
        submitNext(0L, 0L)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun afterTransfer(event: InventoryClickEvent) {
        val desk = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory !== desk.session.inventory) return
        desk.storage.persistTransfer()
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun close(event: InventoryCloseEvent) {
        val desk = sessions[event.player.uniqueId] ?: return
        if (event.inventory !== desk.session.inventory) return
        sessions.remove(event.player.uniqueId, desk)
        returnItems(desk)
    }
    private fun returnItems(desk: Desk) {
        desk.storage.returnItems()
        if (!desk.storage.pending && desk.storage.snapshot().any { it != null }) desk.player.sendMessage(text("return-full",
            "<#ff9f0f>Инвентарь полон. Остаток сохранён у этого NPC — заберите его при следующем открытии."))
    }
    private fun feedback(player: Player, key: String, fallback: String) = player.sendActionBar(text(key, fallback))
    private fun context(vararg values: Pair<String, Component>) = PaperMenuItemRenderContext(values = mapOf(*values))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component =
        TextUtil.mm(config.string("desk.$key", fallback), TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }))
}

internal fun contractPriceGrowth(base: Long, current: Long): String {
    require(base > 0)
    val percent = BigDecimal.valueOf(current - base).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(base), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return "${if (current >= base) "+" else ""}$percent%"
}
