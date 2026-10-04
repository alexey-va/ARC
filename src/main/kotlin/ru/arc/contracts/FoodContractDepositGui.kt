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
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.whenCompleteSync
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtil
import java.util.UUID

/**
 * Deposit adapter for the durable contract transaction. The top never owns
 * player items: offers identify native player slots before journal capture.
 * PaperCloudStorage's storage CAS cannot run an asynchronous payment, so this
 * adapter owns only input; the existing coordinator owns escrow and recovery.
 */
object FoodContractDepositGui : Listener {
    const val GROUP = "food_orders"
    private val menu = ArcMenuSchema.FOOD_CONTRACTS
    private val sessions = mutableMapOf<UUID, Desk>()
    private var tasks: LifecycleTaskScope? = null
    private val config get() = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/contracts.yml")
    private class Desk(val player: Player, var selected: String?) : InventoryHolder {
        lateinit var contents: Inventory
        var pending = false
        override fun getInventory(): Inventory = contents
    }
    fun start() {
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ARC.instance)
    }
    fun shutdown() {
        tasks?.close()
        tasks = null
        HandlerList.unregisterAll(this)
        sessions.values.toList().forEach { if (it.player.openInventory.topInventory === it.contents) it.player.closeInventory() }
        sessions.clear()
    }
    fun open(player: Player, selected: String? = null) {
        if (!ContractOriginGate.canSubmit(player, GROUP)) return
        val views = views(player)
        val desk = Desk(player, views.firstOrNull { it.contract.id == selected }?.contract?.id
            ?: views.firstOrNull { it.contract.itemKey == PaperContractItems.ANY_RAW_FISH }?.contract?.id
            ?: views.firstOrNull()?.contract?.id)
        desk.contents = Bukkit.createInventory(desk, ArcMenus.current().catalog.require(menu).rows * 9,
            text("title", "<#20252b>Матео · продукты для бара"))
        render(desk)
        player.openInventory(desk.contents)
        sessions[player.uniqueId] = desk
    }
    private fun views(player: Player) = ContractsManager.currentPlayerViews(
        player.uniqueId, GROUP, policy = ContractRankPolicyResolver.resolve(player),
    )
    private fun render(desk: Desk) {
        val layout = ArcMenus.current().catalog.require(menu)
        val background = ArcMenus.background(menu)
        (0 until desk.contents.size).forEach { desk.contents.setItem(it, background?.clone()) }
        val views = views(desk.player)
        layout.region(ArcMenuSchema.FOOD_ORDERS).zip(views).forEach { (slot, view) ->
            val item = ArcMenus.item("food-contract-order", context(
                "name" to TextUtil.mm(view.contract.displayName),
                "price" to Component.text(formatContractMoney(view.playerPayoutMinorPerUnit)),
                "remaining" to Component.text(view.playerRemainingQuantity.toString()),
                "state" to text(if (desk.selected == view.contract.id) "selected" else "choose",
                    if (desk.selected == view.contract.id) "<#2bba43>Выбран для сдачи" else "<#92bed8>ЛКМ — выбрать"),
                "accepted" to acceptedItems(view.contract.itemKey),
            ))
            desk.contents.setItem(slot.index, item.withType(PaperContractItems.material(view.contract.itemKey) ?: org.bukkit.Material.PAPER))
        }
        layout.region(ArcMenuSchema.FOOD_DEPOSIT).forEach { desk.contents.setItem(it.index, null) }
        val selected = views.firstOrNull { it.contract.id == desk.selected }
        desk.contents.setItem(layout.slot("info").index, ArcMenus.item(menu, "info", context(
            "name" to (selected?.let { TextUtil.mm(it.contract.displayName) } ?: text("empty", "Нет открытых заказов")),
            "accepted" to (selected?.let { acceptedItems(it.contract.itemKey) } ?: Component.empty()),
            "status" to text(if (desk.pending) "processing" else "instruction",
                if (desk.pending) "<#ff9f0f>Матео принимает продукты…" else "<#e6fff3>Положите продукты в пустые ячейки ниже."),
        )))
    }
    private fun acceptedItems(key: String): Component = if (key == PaperContractItems.ANY_RAW_FISH)
        text("fish", "Треска, лосось, тропическая рыба и иглобрюх. Можно смешивать.")
    else Component.translatable(requireNotNull(PaperContractItems.material(key)).translationKey())

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        val desk = event.view.topInventory.holder as? Desk ?: return
        if (event.whoClicked.uniqueId != desk.player.uniqueId || sessions[desk.player.uniqueId] !== desk || desk.pending) {
            event.isCancelled = true
            return
        }
        val layout = ArcMenus.current().catalog.require(menu)
        if (event.rawSlot in 0 until desk.contents.size) {
            event.isCancelled = true
            if (event.click !in setOf(ClickType.LEFT, ClickType.RIGHT)) return
            val index = layout.region(ArcMenuSchema.FOOD_ORDERS).indexOfFirst { it.index == event.rawSlot }
            if (index >= 0) {
                desk.selected = views(desk.player).getOrNull(index)?.contract?.id ?: desk.selected
                render(desk)
            } else if (layout.region(ArcMenuSchema.FOOD_DEPOSIT).any { it.index == event.rawSlot }) {
                deposit(desk, event.cursor, if (event.isRightClick) 1 else event.cursor.amount, null)
            }
        } else if (event.isShiftClick && event.clickedInventory === desk.player.inventory) {
            event.isCancelled = true
            event.currentItem?.let { deposit(desk, it, it.amount, setOf(event.slot)) }
        } else if (event.click == ClickType.DOUBLE_CLICK) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        val desk = event.view.topInventory.holder as? Desk ?: return
        if (desk.pending) { event.isCancelled = true; return }
        if (event.rawSlots.none { it < desk.contents.size }) return
        event.isCancelled = true
        val slots = ArcMenus.current().catalog.require(menu).region(ArcMenuSchema.FOOD_DEPOSIT).map { it.index }.toSet()
        if (event.rawSlots.all { it in slots })
            deposit(desk, event.oldCursor, event.oldCursor.amount - (event.cursor?.amount ?: 0), null)
    }
    private fun deposit(desk: Desk, offered: ItemStack, amount: Int, sourceSlots: Set<Int>?) {
        val player = desk.player
        val view = views(player).firstOrNull { it.contract.id == desk.selected } ?: return
        val key = view.contract.itemKey
        val material = PaperContractItems.material(key) ?: return
        if (amount <= 0 || !PaperContractItems.isPlainExact(offered, material, key)) {
            feedback(player, "wrong-item", "<#ff9f0f>Этот заказ принимает только указанные обычные сырые продукты.")
            return
        }
        val quote = ContractsManager.quote(player, view.contract.id, minOf(amount, view.maxSubmissionQuantity))
        if (quote == null) {
            feedback(player, "unavailable", "<#ff9f0f>Заказ недоступен: проверьте лимит или снова поговорите с Матео.")
            return
        }
        var slots = sourceSlots
        if (slots == null) {
            // Bukkit owns stacking. Settle the cursor before capturing durable
            // native slots; no items are held in the transient top inventory.
            val before = player.inventory.storageContents.map { it?.clone() }
            val leftovers = player.inventory.addItem(offered.clone().also { it.amount = quote.quantity })
            val moved = quote.quantity - leftovers.values.sumOf { it.amount }
            player.setItemOnCursor(offered.clone().also { it.amount = offered.amount - moved })
            if (moved != quote.quantity) {
                feedback(player, "no-space", "<#ff9f0f>Освободите место в инвентаре или сдайте стопку через Shift + ЛКМ.")
                return
            }
            slots = player.inventory.storageContents.indices.filterTo(linkedSetOf()) { player.inventory.getItem(it) != before[it] }
        }
        desk.pending = true
        render(desk)
        ContractsManager.submit(player, quote, slots).whenCompleteSync(requireNotNull(tasks)) { outcome, failure ->
            desk.pending = false
            if (player.isOnline) {
                if (failure != null || outcome is ContractSubmissionOutcome.ManualReview)
                    feedback(player, "review", "<#c42323>Сдача остановлена для проверки. Не повторяйте её до разбора администратором.")
                else if (outcome is ContractSubmissionOutcome.Committed)
                    player.sendMessage(text("success", "<#2bba43>Матео принял <quantity> шт. · +<price> <white>💰</white>",
                        "quantity" to Component.text(outcome.receipt.quantity),
                        "price" to Component.text(formatContractMoney(outcome.receipt.payoutMinor))))
                else feedback(player, "not-accepted", "<#ff9f0f>Продукты не приняты. Проверьте заказ и инвентарь.")
                if (player.openInventory.topInventory === desk.contents && sessions[player.uniqueId] === desk) render(desk)
            }
        }
    }
    @EventHandler
    fun close(event: InventoryCloseEvent) {
        val desk = event.inventory.holder as? Desk ?: return
        sessions.remove(event.player.uniqueId, desk)
    }
    private fun feedback(player: Player, key: String, fallback: String) = player.sendActionBar(text(key, fallback))
    private fun context(vararg values: Pair<String, Component>) = PaperMenuItemRenderContext(values = mapOf(*values))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component =
        TextUtil.mm(config.string("food-desk.$key", fallback), TagResolver.resolver(values.map { (name, value) ->
            Placeholder.component(name, value)
        }))
}
