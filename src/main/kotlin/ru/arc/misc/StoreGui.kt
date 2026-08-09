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
    private var renderedStoreSlots: List<ItemStack?> = emptyList()

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
        renderedStoreSlots = snapshotVisibleStoreSlots()
        populateStorePane(renderedStoreSlots)
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
     * Patch changed store cells before the click event returns, so the server state matches client prediction.
     * Cursor synchronization remains on the next tick because Bukkit may overwrite it while completing the event.
     */
    private fun scheduleRefresh(
        cursorItem: ItemStack? = null,
        clearCursor: Boolean = false,
    ) {
        refreshItems()
        if (!clearCursor && cursorItem == null) return

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
        val desiredSlots = snapshotVisibleStoreSlots()
        StoreSlotDiff.changedSlots(renderedStoreSlots, desiredSlots).forEach { slot ->
            updateStoreSlot(slot, desiredSlots.getOrNull(slot))
        }
        renderedStoreSlots = desiredSlots.map { it?.clone() }
    }

    private fun snapshotVisibleStoreSlots(): List<ItemStack?> {
        val storeSlots = store.getSlots()
        return List(visibleStoreSlots) { slot -> storeSlots.getOrNull(slot)?.clone() }
    }

    private fun populateStorePane(items: List<ItemStack?>) {
        items.forEachIndexed { slot, item ->
            if (item != null) {
                storePane.addItem(createStoreGuiItem(item), slot % 9, slot / 9)
            }
        }
    }

    /**
     * Keep the IF pane model and the currently open Bukkit inventory aligned without a full GUI redraw.
     */
    private fun updateStoreSlot(
        slot: Int,
        item: ItemStack?,
    ) {
        val x = slot % 9
        val y = slot / 9
        storePane.removeItem(x, y)

        if (item == null || item.type == Material.AIR) {
            chestGui.inventory.setItem(slot, null)
            return
        }

        val paneItem = createStoreGuiItem(item)
        storePane.addItem(paneItem, x, y)

        // StaticPane applies this UUID only while rendering. Apply it to the one slot sent now as well,
        // so InventoryFramework can still match later clicks without calling ChestGui.update().
        val renderedItem = paneItem.copy().also { it.applyUUID() }
        chestGui.inventory.setItem(slot, renderedItem.item.clone())
    }

    private fun createStoreGuiItem(original: ItemStack): GuiItem = GuiItems.create(original.clone())

    private fun scheduleTake(cursorItem: ItemStack) {
        scheduleRefresh(cursorItem = cursorItem)
    }

    private fun applyPlayerStorageTransfer(plan: PlayerStorageTransferPlan) {
        plan.updates.forEach { update ->
            player.inventory.setItem(update.slot, update.item.clone())
        }
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

        val storageTransfer =
            if (click.isShiftClick) {
                VanillaPlayerStorageTransfer.planFull(player.inventory.storageContents, taken)
                    ?: run {
                        val (display, lore) = config.itemComponents("store.no-space")
                        GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
                        return
                    }
            } else {
                null
            }

        if (!store.removeItemAt(storeSlot, taken, amountToRemove)) {
            val (display, lore) = config.itemComponents("store.item-is-gone")
            GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
            scheduleRefresh()
            return
        }

        StoreManager.saveLater(store)
        if (storageTransfer != null) {
            applyPlayerStorageTransfer(storageTransfer)
            scheduleRefresh()
        } else {
            scheduleTake(taken)
        }
    }

    private fun isOnCooldown(player: Player): Boolean = CooldownManager.cooldown(player.uniqueId, "store") != 0L
}
