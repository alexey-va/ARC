package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
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
    private lateinit var paginatedPane: PaginatedPane

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
        builder.onTopClick { click -> handleTopClick(click) { scheduleRefresh(clearCursor = true) } }
        builder.pagination(0 until (rows - 1)) {
            guiItems(buildStoreItems())
        }
        builder.navBar {
            back(configKey = "store.back") {
                player.closeInventory()
                player.performCommand(config.string("store.back-command"))
            }
        }

        chestGui = builder.build()
        paginatedPane =
            builder.paginatedContentPane()
                ?: error("Store GUI must have a paginated content pane")
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
        paginatedPane.clear()
        paginatedPane.populateWithGuiItems(buildStoreItems())
        chestGui.update()
    }

    private fun buildStoreItems(): List<GuiItem> =
        store.getItems().map { original ->
            createStoreGuiItem(original)
        }

    private fun createStoreGuiItem(original: ItemStack): GuiItem {
        val storeItem = original.clone()
        val guiStack = storeItem.clone()

        return GuiItems.create(guiStack) { click ->
            if (click.isCancelled) return@create
            click.isCancelled = true

            debug("[Store] Click on {}", storeItem.type)

            if (isOnCooldown(player)) {
                val (display, lore) = config.itemComponents("store.cooldown")
                GuiUtils.temporaryChange(guiStack, display, lore, 10L) {}
                return@create
            }
            CooldownManager.addCooldown(player.uniqueId, "store", 1L)

            if (click.isShiftClick) {
                if (player.inventory.firstEmpty() == -1 &&
                    player.inventory.none { it != null && it.isSimilar(storeItem) && it.amount < it.maxStackSize }
                ) {
                    val (display, lore) = config.itemComponents("store.no-space")
                    GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
                    return@create
                }
            }

            val amountToRemove =
                if (click.isRightClick) {
                    storeItem.amount / 2 + storeItem.amount % 2
                } else {
                    storeItem.amount
                }
            val taken = storeItem.clone().also { it.amount = amountToRemove }

            val success = store.removeItem(taken, amountToRemove)
            if (success) {
                StoreManager.saveLater(store)
                if (click.isShiftClick) {
                    depositToPlayerInventory(taken)
                    scheduleRefresh()
                } else {
                    scheduleTake(taken)
                }
            } else {
                val (display, lore) = config.itemComponents("store.item-is-gone")
                GuiUtils.temporaryChange(guiStack, display, lore, 40L) {}
                scheduleRefresh()
            }
        }
    }

    /**
     * Refresh store slots, then apply cursor — order matters: [ChestGui.update] resets cursor if set earlier.
     */
    private fun scheduleTake(cursorItem: ItemStack) {
        scheduleRefresh(cursorItem = cursorItem)
    }

    /** Shift-click from store: move stack into player inventory (vanilla chest behavior). */
    private fun depositToPlayerInventory(item: ItemStack) {
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { remaining ->
            if (remaining.amount > 0) {
                store.addItem(remaining)
                StoreManager.saveLater(store)
            }
        }
    }

    private fun handleBottomClick(
        click: org.bukkit.event.inventory.InventoryClickEvent,
        refresh: () -> Unit,
    ) {
        if (!click.isShiftClick) return
        if (!store.hasSpace()) return
        val currentItem = click.currentItem ?: return

        click.isCancelled = true

        if (!store.addItem(currentItem.clone())) return

        click.currentItem = null
        StoreManager.saveLater(store)
        refresh()
    }

    private fun handleTopClick(
        click: org.bukkit.event.inventory.InventoryClickEvent,
        refresh: () -> Unit,
    ) {
        val currentStoreItem = click.currentItem
        val cursor = click.cursor

        val hasCurrentItem = currentStoreItem != null && currentStoreItem.type != Material.AIR
        val hasCursorItem = cursor.type != Material.AIR
        val hasStoreSpace = store.hasSpace()

        if (hasCurrentItem) return
        if (!hasStoreSpace && hasCursorItem) return

        click.isCancelled = true
        if (!hasCursorItem) return

        if (!store.addItem(cursor.clone())) return

        StoreManager.saveLater(store)
        refresh()
    }

    private fun isOnCooldown(player: Player): Boolean = CooldownManager.cooldown(player.uniqueId, "store") != 0L
}
