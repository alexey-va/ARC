package ru.arc.enchanting

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.menus.premade.ItemEnchantmentMenuConfig
import com.magmaguy.elitemobs.economy.EconomyHandler
import com.magmaguy.elitemobs.items.upgradesystem.EnchantmentProgression
import com.magmaguy.elitemobs.items.upgradesystem.EnchantmentAcquisition
import com.magmaguy.elitemobs.items.upgradesystem.UpgradeSystem
import com.magmaguy.elitemobs.menus.ItemEnchantmentMenu
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.eliteloot.isEliteEnchantmentBook
import ru.arc.eliteloot.presentEliteItem
import ru.arc.util.Logging
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ThreadLocalRandom

internal class EliteEnchantingController(private val config: EnchantingConfig) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val opening = mutableSetOf<UUID>()
    private val menus = mutableMapOf<UUID, Inventory>()
    private val processing = mutableSetOf<Inventory>()
    private val shown = mutableMapOf<Inventory, ShownEnchantment>()

    private data class ShownEnchantment(
        val item: ItemStack, val book: ItemStack, val ticket: ItemStack?,
        val quote: EnchantmentProgression.Quote,
    )

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBookDrop(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== player.inventory || event.click !in setOf(ClickType.LEFT, ClickType.RIGHT)) return
        val book = event.cursor.takeUnless { it.type.isAir } ?: return
        if (!isEliteEnchantmentBook(book)) return
        // An EM book owns this gesture even on an ineligible target. AE books never enter here.
        event.isCancelled = true
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            player.sendMessage(config.text("messages.unavailable"))
            return
        }
        if (menus.containsKey(player.uniqueId) || !opening.add(player.uniqueId)) return
        val target = event.currentItem
        if (target == null || !EliteItemManager.isEliteMobsItem(target) || target.type == Material.ENCHANTED_BOOK) {
            opening.remove(player.uniqueId)
            player.sendMessage(config.text("messages.elite-only"))
            return
        }
        if (runCatching { UpgradeSystem.preview(target, book) }.isFailure) {
            opening.remove(player.uniqueId)
            player.sendMessage(config.text("messages.incompatible"))
            return
        }
        val view = event.view
        val slot = event.slot
        val expectedTarget = target.clone()
        val expectedBook = book.clone()
        tasks.runLater(1) {
            try {
                if (!player.isOnline || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs") ||
                    player.openInventory.topInventory !== view.topInventory ||
                    !enchantmentInputsMatch(expectedTarget, expectedBook, player.inventory.getItem(slot), player.itemOnCursor)) return@runLater
                openNativeConfirmation(player, view, slot, expectedTarget, expectedBook)
            } finally {
                opening.remove(player.uniqueId)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onConfirm(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val menu = menus[player.uniqueId] ?: return
        if (event.view.topInventory !== menu) return
        if (menu in processing) {
            event.isCancelled = true
            return
        }
        if (event.clickedInventory !== menu || event.slot != ItemEnchantmentMenuConfig.getConfirmSlot()) return
        // EM's native HIGHEST listener ignores this cancelled event. Exactly one owner purchases.
        event.isCancelled = true
        if (event.click != ClickType.LEFT || !processing.add(menu)) return
        tasks.runLater(1) {
            try {
                if (event.isCancelled && player.isOnline && menus[player.uniqueId] === menu && player.openInventory.topInventory === menu) {
                    confirm(player, menu)
                }
            } finally {
                processing.remove(menu)
            }
        }
    }

    private fun confirm(player: Player, menu: Inventory) {
        var attempt: EnchantmentAcquisition? = null
        try {
            check(org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs"))
            val itemSlot = ItemEnchantmentMenuConfig.getItemSlot()
            val bookSlot = ItemEnchantmentMenuConfig.getEnchantedBookSlot()
            val ticketSlot = ItemEnchantmentMenuConfig.getLuckyTicketSlot()
            val item = menu.getItem(itemSlot) ?: return
            val book = menu.getItem(bookSlot) ?: return
            val ticket = menu.getItem(ticketSlot)
            val displayed = shown[menu]
            attempt = EnchantmentAcquisition(player, item, book, ticket)
            if (displayed == null || displayed.item != item || displayed.book != book ||
                displayed.ticket != ticket || displayed.quote != attempt.quote()) {
                refresh(player, menu)
                player.sendMessage(config.text("messages.preview-changed"))
                return
            }
            if (!attempt.purchase(menu, itemSlot, bookSlot, ticketSlot) {
                    menus[player.uniqueId] === menu && player.openInventory.topInventory === menu &&
                        org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs")
                }) {
                player.sendMessage(config.text("messages.purchase-failed"))
                return
            }
            // Native purchase cleared the input slots. Native close cannot return them twice.
            player.closeInventory()
            val outcome = eliteEnchantmentOutcome(attempt.quote(), ThreadLocalRandom.current().nextDouble())
            when (outcome) {
                EliteEnchantmentOutcome.SUCCESS -> attempt.success()
                EliteEnchantmentOutcome.FAILURE -> attempt.failure()
                EliteEnchantmentOutcome.DESTROYED -> attempt.criticalFailure()
            }
            player.sendMessage(config.text("messages.${outcome.name.lowercase()}"))
        } catch (failure: Exception) {
            // Only EM knows whether custody/payment was acquired and whether settlement completed.
            attempt?.abort("ARC enchantment confirmation failed: $failure")
            player.closeInventory()
            Logging.error("EliteEnchanting confirmation failed player={}", player.uniqueId, failure)
            player.sendMessage(config.text("messages.unavailable"))
        }
    }

    /** The transfer is synchronous; the native menu owns both inputs only after it has opened. */
    private fun openNativeConfirmation(player: Player, oldView: InventoryView, slot: Int, target: ItemStack, book: ItemStack) {
        var handedOver: Inventory? = null
        try {
            val native = transferEliteBookToConfirmation(player, slot, target, book,
                ItemEnchantmentMenuConfig.getItemSlot(), ItemEnchantmentMenuConfig.getEnchantedBookSlot()) {
                ItemEnchantmentMenu(player)
                player.openInventory.topInventory.also {
                    check(player.isOnline && it !== oldView.topInventory && it.size == 54 && it.holder === player) {
                        "Native EliteMobs enchantment menu was not opened"
                    }
                }
            }
            handedOver = native
            menus[player.uniqueId] = native
            refresh(player, native)
        } catch (failure: Exception) {
            if (handedOver != null && player.openInventory.topInventory === handedOver) {
                // Native close returns the inputs. Never also refund them here.
                player.closeInventory()
            }
            Logging.error("EliteEnchanting open failed player={} handedOver={}", player.uniqueId, handedOver != null, failure)
            player.sendMessage(config.text("messages.unavailable"))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onNativeClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val menu = menus[player.uniqueId] ?: return
        if (event.view.topInventory !== menu) return
        // EM updates its quote synchronously after book/ticket changes; decorate that final state.
        tasks.runLater(1) {
            if (player.isOnline && menus[player.uniqueId] === menu && player.openInventory.topInventory === menu) {
                runCatching { refresh(player, menu) }.onFailure { failure ->
                    Logging.error("EliteEnchanting preview failed player={}", player.uniqueId, failure)
                    player.closeInventory()
                    player.sendMessage(config.text("messages.unavailable"))
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        if (menus[event.player.uniqueId] === event.inventory) menus.remove(event.player.uniqueId)
        shown.remove(event.inventory)
    }

    private fun refresh(player: Player, menu: Inventory) {
        val item = menu.getItem(ItemEnchantmentMenuConfig.getItemSlot())
        val book = menu.getItem(ItemEnchantmentMenuConfig.getEnchantedBookSlot())
        menu.setItem(ItemEnchantmentMenuConfig.getInfoSlot(), config.button(Material.ENCHANTED_BOOK, "menu.info"))
        menu.setItem(ItemEnchantmentMenuConfig.getCancelSlot(), config.button(Material.BARRIER, "menu.cancel"))
        menu.setItem(ItemEnchantmentMenuConfig.getEnchantedBookInfoSlot(), config.button(Material.BOOK, "menu.book"))
        menu.setItem(ItemEnchantmentMenuConfig.getLuckyTicketInfoSlot(), config.button(Material.PAPER, "menu.ticket"))
        if (item == null || book == null) {
            shown.remove(menu)
            menu.setItem(ItemEnchantmentMenuConfig.getItemInfoSlot(), config.button(Material.GRAY_DYE, "menu.empty"))
            menu.setItem(ItemEnchantmentMenuConfig.getConfirmSlot(), config.button(Material.GRAY_DYE, "menu.empty"))
            return
        }
        val preview = UpgradeSystem.upgrade(item, book)
        val quote = EnchantmentProgression.quote(item, menu.getItem(ItemEnchantmentMenuConfig.getLuckyTicketSlot()) != null)
        presentEliteItem(preview, player)
        preview.editMeta { meta ->
            meta.lore(listOf(Component.empty(), config.text("menu.result"), Component.empty()) + meta.lore().orEmpty())
        }
        menu.setItem(ItemEnchantmentMenuConfig.getItemInfoSlot(), preview)
        menu.setItem(ItemEnchantmentMenuConfig.getConfirmSlot(), config.button(Material.EMERALD, "menu.confirm",
            Placeholder.unparsed("price", EconomyHandler.formatCurrency(quote.price().toDouble())),
            Placeholder.unparsed("success", enchantmentPercent(quote.success() + quote.challenge())),
            Placeholder.unparsed("failure", enchantmentPercent(quote.failure())),
            Placeholder.unparsed("destroy", enchantmentPercent(quote.criticalFailure())),
            Placeholder.unparsed("challenge", enchantmentPercent(quote.challenge())),
        ))
        shown[menu] = ShownEnchantment(item.clone(), book.clone(),
            menu.getItem(ItemEnchantmentMenuConfig.getLuckyTicketSlot())?.clone(), quote)
    }

    override fun close() {
        tasks.close()
        opening.clear()
        menus.toMap().forEach { (id, inventory) ->
            org.bukkit.Bukkit.getPlayer(id)?.takeIf { it.openInventory.topInventory === inventory }?.closeInventory()
        }
        menus.clear()
        shown.clear()
        processing.clear()
    }
}

internal fun enchantmentPercent(value: Double): String =
    BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal enum class EliteEnchantmentOutcome { SUCCESS, FAILURE, DESTROYED }

/** Retain native probability intervals; the former challenge interval now succeeds immediately. */
internal fun eliteEnchantmentOutcome(quote: EnchantmentProgression.Quote, roll: Double): EliteEnchantmentOutcome {
    require(roll.isFinite() && roll >= 0 && roll < 1)
    if (roll < quote.success()) return EliteEnchantmentOutcome.SUCCESS
    if (roll < quote.success() + quote.criticalFailure()) return EliteEnchantmentOutcome.DESTROYED
    if (roll < quote.success() + quote.criticalFailure() + quote.challenge()) return EliteEnchantmentOutcome.SUCCESS
    return EliteEnchantmentOutcome.FAILURE
}
