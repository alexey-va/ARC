package ru.arc.enchanting

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.items.upgradesystem.UpgradeSystem
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.eliteloot.isEliteEnchantmentBook
import ru.arc.eliteloot.isEliteBookNativeCompatible
import ru.arc.eliteloot.presentEliteItem
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

internal class EliteEnchantingController(private val config: EnchantingConfig) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val applying = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBookDrop(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val creative = event is InventoryCreativeEvent && player.gameMode == GameMode.CREATIVE
        if (event.clickedInventory != player.inventory ||
            (!creative && event.click !in setOf(ClickType.LEFT, ClickType.RIGHT))) return
        val book = event.cursor.takeUnless { it.type.isAir } ?: return
        if (!isEliteEnchantmentBook(book)) return
        val target = event.currentItem?.takeUnless { it.type.isAir || it.type == Material.ENCHANTED_BOOK } ?: return
        // Empty slots and other books keep normal inventory movement, stacking and splitting.
        // An EM book owns this gesture even on an ineligible target. AE books never enter here.
        event.isCancelled = true
        // Creative sends a proposed replacement stack, not a server-owned cursor. Paper clears
        // the client cursor after DENY. Admit that stack into inventory once, before deferring,
        // so invalid targets and disconnects cannot lose it.
        val bookInventorySlot = if (creative) admitCreativeBook(player, book) ?: return else null
        if (event.view.topInventory !== player.openInventory.topInventory) return
        // Even an overlapping creative packet must secure its proposed book before DENY clears it.
        if (!applying.add(player.uniqueId)) return
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            applying.remove(player.uniqueId)
            player.sendMessage(config.text("messages.unavailable"))
            return
        }
        if (!EliteItemManager.isEliteMobsItem(target)) {
            applying.remove(player.uniqueId)
            player.sendMessage(config.text("messages.elite-only"))
            return
        }
        if (target.amount != 1 || !isEliteBookNativeCompatible(target, book)) {
            applying.remove(player.uniqueId)
            player.sendMessage(config.text("messages.incompatible-target"))
            return
        }
        if (runCatching { UpgradeSystem.preview(target, book) }.isFailure) {
            applying.remove(player.uniqueId)
            player.sendMessage(config.text("messages.incompatible"))
            return
        }
        val view = event.view
        val slot = event.slot
        val expectedTarget = target.clone()
        val expectedBook = (bookInventorySlot?.let(player.inventory::getItem) ?: book).clone()
        tasks.runLater(1) {
            try {
                if (!player.isOnline || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("EliteMobs") ||
                    player.openInventory.topInventory !== view.topInventory ||
                    !enchantmentInputsMatch(expectedTarget, expectedBook, player.inventory.getItem(slot),
                        if (bookInventorySlot == null) player.itemOnCursor else player.inventory.getItem(bookInventorySlot))) return@runLater
                applyDirect(player, slot, expectedTarget, expectedBook, bookInventorySlot)
            } finally {
                applying.remove(player.uniqueId)
            }
        }
    }

    private fun applyDirect(player: Player, slot: Int, target: ItemStack, book: ItemStack, bookInventorySlot: Int?) {
        try {
            val chances = readEliteBookChances(book) ?: run {
                val updated = book.clone()
                presentEliteItem(updated, player)
                if (bookInventorySlot == null) player.setItemOnCursor(updated)
                else player.inventory.setItem(bookInventorySlot, updated)
                player.sendMessage(config.text("messages.book-updated"))
                return
            }
            // Prepare all provider work before consuming anything. No native purchase/menu is used:
            // the book owns the displayed probabilities and application has no separate fee.
            val upgraded = UpgradeSystem.upgrade(target.clone(), book.clone())
            presentEliteItem(upgraded, player)
            val random = ThreadLocalRandom.current()
            val outcome = bookApplicationOutcome(chances, random.nextDouble(), random.nextDouble())
            val result = when (outcome) {
                EliteEnchantmentOutcome.SUCCESS -> upgraded
                EliteEnchantmentOutcome.FAILURE -> target
                EliteEnchantmentOutcome.DESTROYED -> null
            }
            applyBookInInventory(player, slot, target, book, result, bookInventorySlot)
            player.updateInventory()
            player.sendMessage(config.text("messages.${outcome.name.lowercase()}"))
        } catch (failure: Exception) {
            Logging.error("EliteEnchanting direct application failed player={}", player.uniqueId, failure)
            player.sendMessage(config.text("messages.unavailable"))
        }
    }

    override fun close() {
        tasks.close()
        applying.clear()
    }
}
