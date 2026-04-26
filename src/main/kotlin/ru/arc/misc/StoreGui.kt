package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.gui.GuiItems
import ru.arc.gui.dynamicGui
import ru.arc.gui.onBottomClick
import ru.arc.gui.onTopClick
import ru.arc.gui.onTopDrag
import ru.arc.store.StoreData
import ru.arc.util.CooldownManager
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
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
    ): ChestGui {
        val rows = min(6, ceil(store.size.toDouble() / 9).toInt() + 1)

        // Build item list with click handlers
        val storeItems = buildStoreItems(player, store)

        return dynamicGui(
            title = config.string("store.title"),
            itemCount = store.size,
            minRows = 2,
            maxRows = 6,
            navRows = 1,
            player = player,
            config = config,
        ) {
            navBackground()

            // Top drag - cancel to prevent item duplication
            onTopDrag { it.isCancelled = true }

            // Bottom click - shift-click items from player inventory to store
            onBottomClick { click ->
                handleBottomClick(click, player, store) {
                    // Rebuild and show updated GUI
                    GuiUtils.constructAndShowAsync({ create(player, store) }, player)
                }
            }

            // Top click - handle placing items from cursor into empty store slots
            onTopClick { click ->
                handleTopClick(click, store) {
                    // Rebuild and show updated GUI
                    GuiUtils.constructAndShowAsync({ create(player, store) }, player)
                }
            }

            pagination(0 until (rows - 1)) {
                guiItems(storeItems)
            }

            navBar {
                val cfg = this@StoreGuiFactory.config // Capture factory config
                back(
                    configKey = "store.back",
                    modelData = cfg.integer("store.back-model-data", 11013),
                ) {
                    player.closeInventory()
                    player.performCommand(cfg.string("store.back-command"))
                }
            }
        }
    }

    /**
     * Build store items with click handlers.
     */
    private fun buildStoreItems(
        player: Player,
        store: StoreData,
    ): List<GuiItem> =
        store.getItems().map { original ->
            createStoreGuiItem(original, player, store) { gui ->
                GuiUtils.constructAndShowAsync({ create(player, store) }, player)
            }
        }

    /**
     * Create a single store item with click handling.
     */
    private fun createStoreGuiItem(
        original: ItemStack,
        player: Player,
        store: StoreData,
        refreshGui: (ChestGui) -> Unit,
    ): GuiItem {
        val storeItem = original.clone()
        val guiStack = storeItem.clone()

        return GuiItems.create(guiStack) { click ->
            if (click.isCancelled) return@create
            click.isCancelled = true

            info("Clicked on item: {}", storeItem.type)

            // Check cooldown
            if (isOnCooldown(player)) {
                info("On cooldown")
                GuiUtils.temporaryChange(
                    guiStack,
                    TextUtil.mm(config.string("store.cooldown-title"), true),
                    config.stringList("store.cooldown-lore").map { TextUtil.mm(it, true) },
                    20L,
                ) {}
                return@create
            }

            CooldownManager.addCooldown(player.uniqueId, "store", 5L)

            // Check inventory space
            val hasInvSpace = player.inventory.firstEmpty() != -1
            if (!hasInvSpace) {
                info("No space")
                GuiUtils.temporaryChange(
                    guiStack,
                    TextUtil.mm(config.string("store.no-space-title"), true),
                    config.stringList("store.no-space-lore").map { TextUtil.mm(it, true) },
                    60L,
                ) {}
                return@create
            }

            // Calculate amount to remove (right-click = half)
            val amountToRemove =
                if (click.isRightClick) {
                    storeItem.amount / 2 + storeItem.amount % 2
                } else {
                    storeItem.amount
                }
            storeItem.amount = amountToRemove

            val success = store.removeItem(storeItem, amountToRemove)

            if (success) {
                info("Success removing {}", storeItem)
                if (click.cursor.type != Material.AIR || click.isShiftClick) {
                    info("Cursor not empty")
                    player.inventory.addItem(storeItem)
                } else {
                    info("Cursor empty")
                    @Suppress("DEPRECATION")
                    click.setCursor(storeItem)
                }
            } else {
                GuiUtils.temporaryChange(
                    guiStack,
                    TextUtil.mm(config.string("store.item-is-gone-display"), true),
                    config.stringList("store.item-is-gone-lore").map { TextUtil.mm(it, true) },
                    60L,
                ) {}
            }

            // Rebuild GUI
            GuiUtils.constructAndShowAsync({ create(player, store) }, player)
        }
    }

    /**
     * Handle bottom inventory (player inventory) shift-click.
     */
    private fun handleBottomClick(
        click: org.bukkit.event.inventory.InventoryClickEvent,
        player: Player,
        store: StoreData,
        refresh: () -> Unit,
    ) {
        if (!click.isShiftClick) {
            info("B: Not shift click")
            return
        }
        if (!store.hasSpace()) {
            info("B: No space")
            return
        }
        val currentItem =
            click.currentItem ?: run {
                info("B: No item")
                return
            }

        click.isCancelled = true

        val success = store.addItem(currentItem.clone())
        if (!success) {
            info("B: Not success")
            return
        }

        click.currentItem = null
        refresh()
    }

    /**
     * Handle top inventory click (placing items from cursor).
     */
    private fun handleTopClick(
        click: org.bukkit.event.inventory.InventoryClickEvent,
        store: StoreData,
        refresh: () -> Unit,
    ) {
        val currentStoreItem = click.currentItem
        val cursor = click.cursor

        val hasCurrentItem = currentStoreItem != null && currentStoreItem.type != Material.AIR
        val hasCursorItem = cursor.type != Material.AIR
        val hasStoreSpace = store.hasSpace()

        if (hasCurrentItem) {
            info("T: Current item: ${currentStoreItem.type}")
            return
        }
        if (!hasStoreSpace && hasCursorItem) {
            info("T: No space")
            return
        }

        click.isCancelled = true

        if (!hasCursorItem) return

        val addedSuccess = store.addItem(cursor.clone())

        if (addedSuccess) {
            info("T: Added success")
            currentStoreItem?.let { item ->
                item.editMeta { meta ->
                    meta.persistentDataContainer.remove(NamespacedKey(ARC.instance, "if-uuid"))
                }
            }
            @Suppress("DEPRECATION")
            click.setCursor(currentStoreItem)
            refresh()
        }
    }

    /**
     * Check if player is on store cooldown.
     */
    private fun isOnCooldown(player: Player): Boolean = CooldownManager.cooldown(player.uniqueId, "store") != 0L
}
