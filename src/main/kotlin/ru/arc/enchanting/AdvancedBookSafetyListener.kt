package ru.arc.enchanting

import net.advancedplugins.ae.api.AEAPI
import net.advancedplugins.ae.api.EnchantApplyEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.eliteloot.isAdvancedEnchantmentsBook
import ru.arc.util.Logging
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Native AE validates compatibility/slots; ARC owns the displayed independent success/risk rolls. */
internal class AdvancedBookSafetyListener(private val config: EnchantingConfig) : Listener, AutoCloseable {
    private val clicks = mutableMapOf<UUID, InventoryClickEvent>()
    private val applying = mutableSetOf<UUID>()
    private val tasks = LifecycleTaskScope()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBookClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val book = event.cursor
        if (!isAdvancedEnchantmentsBook(book)) return
        val normalized = presentAdvancedBook(normalizeAdvancedBookRisk(book, player), player)
        if (!isAdvancedBookRiskSafe(normalized)) {
            event.isCancelled = true
            if (event is InventoryCreativeEvent && player.gameMode == GameMode.CREATIVE) {
                admitCreativeBook(player, book)
            }
            player.sendMessage(config.text("messages.unavailable"))
            return
        }
        if (normalized != book) event.setCursor(normalized)
        if (event.clickedInventory == player.inventory && event.currentItem?.let {
                !it.type.isAir && it.type != Material.ENCHANTED_BOOK
            } == true) {
            clicks[player.uniqueId] = event
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClickFinished(event: InventoryClickEvent) {
        if (clicks[event.whoClicked.uniqueId] !== event) return
        clicks.remove(event.whoClicked.uniqueId)
        // Rejected native validation also denies a creative proposed cursor. Keep that book.
        val player = event.whoClicked as? Player ?: return
        if (event is InventoryCreativeEvent && player.gameMode == GameMode.CREATIVE && event.isCancelled) {
            admitCreativeBook(player, event.cursor)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNativeApply(event: EnchantApplyEvent) {
        val player = event.player
        val click = clicks.remove(player.uniqueId) ?: return // excludes anvils and other provider entry points
        val book = click.cursor
        // This event is emitted after native material, conflicts, level, permission and slot checks.
        // Cancelling it prevents AE 9.24.15's coupled/reversed probability calculation and consumption.
        event.isCancelled = true
        click.isCancelled = true
        val creative = click is InventoryCreativeEvent && player.gameMode == GameMode.CREATIVE
        val bookSlot = if (creative) admitCreativeBook(player, book) ?: return else null
        val target = click.currentItem?.clone() ?: return
        // AE removes replaced/conflicting enchants from its validated clone before this event.
        // Failures retain the original; only success uses that transformed clone.
        val validatedTarget = event.item.clone()
        if (event.book != book || validatedTarget.type != target.type) {
            player.sendMessage(config.text("messages.unavailable"))
            return
        }
        if (!applying.add(player.uniqueId)) return
        val expectedBook = (if (bookSlot == null) player.itemOnCursor else player.inventory.getItem(bookSlot))?.clone()
        if (expectedBook == null || target.amount != 1) {
            applying.remove(player.uniqueId)
            return
        }
        val slot = click.slot
        val view = click.view.topInventory
        tasks.runLater(1) {
            try {
                if (!player.isOnline || !Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments") ||
                    player.openInventory.topInventory !== view ||
                    !enchantmentInputsMatch(target, expectedBook, player.inventory.getItem(slot),
                        if (bookSlot == null) player.itemOnCursor else player.inventory.getItem(bookSlot))) return@runLater
                val chances = advancedBookChances(expectedBook)
                val random = ThreadLocalRandom.current()
                var outcome = bookApplicationOutcome(chances, random.nextDouble(), random.nextDouble())
                var protected = false
                val result = when (outcome) {
                    EliteEnchantmentOutcome.SUCCESS -> AEAPI.applyEnchant(
                        AEAPI.getBookEnchantment(expectedBook), AEAPI.getBookEnchantmentLevel(expectedBook),
                        AEAPI.getEnchantLevel(AEAPI.getBookEnchantment(expectedBook), target) > 0, true, validatedTarget,
                    )
                    EliteEnchantmentOutcome.FAILURE -> target
                    EliteEnchantmentOutcome.DESTROYED -> {
                        val saved = protectedAdvancedBookTarget(target)
                        if (saved != null) {
                            protected = true
                            saved
                        } else {
                            if (advancedDestructionCancelled(event, target.clone(), player, expectedBook.clone())) {
                                outcome = EliteEnchantmentOutcome.FAILURE
                                target
                            } else null
                        }
                    }
                }
                applyBookInInventory(player, slot, target, expectedBook, result, bookSlot)
                player.updateInventory()
                player.sendMessage(config.text(if (protected) "messages.protected" else "messages.${outcome.name.lowercase()}"))
            } catch (failure: Exception) {
                Logging.error("Advanced enchantment direct application failed player={}", player.uniqueId, failure)
                player.sendMessage(config.text("messages.unavailable"))
            } finally {
                applying.remove(player.uniqueId)
            }
        }
    }

    override fun close() {
        tasks.close()
        clicks.clear()
        applying.clear()
    }
}
