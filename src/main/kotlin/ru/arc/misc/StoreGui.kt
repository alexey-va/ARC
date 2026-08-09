package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.sync
import ru.arc.gui.GuiBuilder
import ru.arc.gui.GuiItems
import ru.arc.gui.onBottomClick
import ru.arc.gui.onTopClick
import ru.arc.gui.onTopDrag
import ru.arc.store.StoreData
import ru.arc.store.StoreManager
import ru.arc.util.CooldownManager
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.debug
import ru.arc.util.itemComponents
import kotlin.math.ceil
import kotlin.math.min

/**
 * Factory for creating StoreGui.
 */
object StoreGuiFactory {
    private val config: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/store.yml")
    }

    /**
     * Creates a store GUI for the given player.
     */
    fun create(
        player: Player,
        store: StoreData,
    ): ChestGui = StoreGuiSession(player, store, config).build()
}

/**
 * Live store GUI session — refreshes slots in place instead of reopening the inventory.
 */
private class StoreGuiSession(
    private val player: Player,
    private val store: StoreData,
    private val config: Config,
) {
    private lateinit var chestGui: ChestGui
    private lateinit var storePane: StaticPane
    private var visibleStoreSlots: Int = 0

    fun build(): ChestGui {
        val rows = min(6, ceil(store.size.toDouble() / 9).toInt() + 1)
        val builder =
            GuiBuilder(
                config.string("store.title"),
                rows,
                player,
                config,
            )

        builder.navBackground()
        builder.onTopDrag { it.isCancelled = true }
        builder.onBottomClick { click -> handleBottomClick(click) { scheduleRefresh() } }
        builder.onTopClick { click -> handleTopClick(click) }
        visibleStoreSlots = (rows - 1) * 9
        storePane = StaticPane(9, rows - 1)
        populateStorePane()
        builder.gui.addPane(Slot.fromXY(0, 0), storePane)
        builder.navBar {
            back(configKey = "store.back") {
                player.closeInventory()
                player.performCommand(config.string("store.back-command"))
            }
        }

        chestGui = builder.build()
        return chestGui
    }

    /**
     * Refresh store slots on the next tick, then sync cursor.
     * [ChestGui.update] resets cursor if the item was set before refresh.
     */
    private fun scheduleRefresh(
        cursorItem: ItemStack? = null,
        clearCursor: Boolean = false,
    ) {
        sync {
            refreshItems()
            when {
                clearCursor -> player.setItemOnCursor(null)
                cursorItem != null -> player.setItemOnCursor(cursorItem)
            }
        }
    }

    private fun refreshItems() {
        if (!chestGui.viewers.contains(player)) return
        storePane.clear()
        populateStorePane()
        chestGui.update()
    }

    private fun populateStorePane() {
        store.getSlots().take(visibleStoreSlots).forEachIndexed { slot, item ->
            if (item != null) {
                storePane.addItem(createStoreGuiItem(item), slot % 9, slot / 9)
            }
        }
    }

    private fun createStoreGuiItem(original: ItemStack): GuiItem = GuiItems.create(original.clone())

    /**
     * Refresh store slots, then apply cursor — order matters: [ChestGui.update] resets cursor if set earlier.
     */
    private fun scheduleTake(cursorItem: ItemStack) {
        scheduleRefresh(cursorItem = cursorItem)
    }

    /** Shift-click from store: move stack into player inventory (vanilla chest behavior). */
    private fun depositToPlayerInventory(
        sourceSlot: Int,
        item: ItemStack,
    ): ItemStack? {
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { remaining ->
            if (remaining.amount > 0 && !store.addItemAt(sourceSlot, remaining)) {
                debug("[Store] Source slot {} changed while returning inventory leftover", sourceSlot)
                return remaining.clone()
            }
            StoreManager.saveLater(store)
        }
        return null
    }

    private fun canFitPlayerInventory(item: ItemStack): Boolean {
        var remaining = item.amount
        for (stack in player.inventory.storageContents) {
            remaining -=
                when {
                    stack == null || stack.type == Material.AIR -> item.maxStackSize
                    stack.isSimilar(item) -> (stack.maxStackSize - stack.amount).coerceAtLeast(0)
                    else -> 0
                }
            if (remaining <= 0) return true
        }
        return false
    }

    private fun handleBottomClick(
        click: org.bukkit.event.inventory.InventoryClickEvent,
        refresh: () -> Unit,
    ) {
        if (!click.isShiftClick) return
        val currentItem = click.currentItem ?: return
        if (!store.canAddItem(currentItem)) return

        click.isCancelled = true

        if (!store.addItem(currentItem.clone())) return

        click.currentItem = null
        StoreManager.saveLater(store)
        refresh()
    }

    private fun handleTopClick(click: org.bukkit.event.inventory.InventoryClickEvent) {
        val storeSlot = click.rawSlot
        if (storeSlot !in 0 until minOf(store.size, visibleStoreSlots)) return

        click.isCancelled = true

        val currentStoreItem = store.getItemAt(storeSlot)
        val cursor = click.cursor
        val hasCursorItem = cursor.type != Material.AIR

        if (currentStoreItem == null) {
            if (!hasCursorItem || !store.addItemAt(storeSlot, cursor.clone())) return
            StoreManager.saveLater(store)
            scheduleRefresh(clearCursor = true)
            return
        }

        val guiStack = click.currentItem ?: currentStoreItem.clone()
        debug("[Store] Click on {} in slot {}", currentStoreItem.type, storeSlot)

        if (isOnCooldown(player)) {
            val (display, lore) = config.itemComponents("store.cooldown")
            GuiUtils.temporaryChange(guiStack, display, lore, 10L) {}
            return
        }
        CooldownManager.addCooldown(player.uniqueId, "store", 1L)

        if (!click.isShiftClick && hasCursorItem) {
            if (!cursor.isSimilar(currentStoreItem)) return

            val available = (currentStoreItem.maxStackSize - currentStoreItem.amount).coerceAtLeast(0)
            val amountToAdd = if (click.isRightClick) minOf(1, available) else minOf(cursor.amount, available)
            if (amountToAdd <= 0) return

            val deposited = cursor.clone().also { it.amount = amountToAdd }
            if (!store.addItemAt(storeSlot, deposited)) {
                scheduleRefresh(cursorItem = cursor.clone())
                return
            }

            val remaining =
                (cursor.amount - amountToAdd).takeIf { it > 0 }?.let { amount ->
                    cursor.clone().also { it.amount = amount }
                }
            StoreManager.saveLater(store)
            scheduleRefresh(cursorItem = remaining, clearCursor = remaining == null)
            return
        }

        val amountToRemove =
            if (click.isRightClick) {
                currentStoreItem.amount / 2 + currentStoreItem.amount % 2
            } else {
                currentStoreItem.amount
            }
        val taken = currentStoreItem.clone().also { it.amount = amountToRemove }

        if (click.isShiftClick && !canFitPlayerInventory(taken)) {
            val (display, lore) = config.itemComponents("store.no-space")
            GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
            return
        }

        if (!store.removeItemAt(storeSlot, taken, amountToRemove)) {
            val (display, lore) = config.itemComponents("store.item-is-gone")
            GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
            scheduleRefresh()
            return
        }

        StoreManager.saveLater(store)
        if (click.isShiftClick) {
            scheduleRefresh(cursorItem = depositToPlayerInventory(storeSlot, taken))
        } else {
            scheduleTake(taken)
        }
    }

    private fun isOnCooldown(player: Player): Boolean = CooldownManager.cooldown(player.uniqueId, "store") != 0L
}
