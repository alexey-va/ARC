package ru.arc.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.modules.EconomyModule
import ru.arc.util.CooldownManager
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import java.util.UUID

// ==================== Click Action DSL ====================

// Note: ClickActionBuilder is defined in GuiDsl.kt as ItemBuilder.ClickActionBuilder
// Use clicks { left { } right { } } for multi-action handlers

// ==================== Click Context Helpers ====================

/**
 * Rich click context with helper methods.
 *
 * Example:
 * ```kotlin
 * onClick { click ->
 *     click.ifOnCooldown("purchase", 20) { return@onClick }
 *     click.requireBalance(1000.0) ?: return@onClick
 *     click.requirePermission("vip") ?: return@onClick
 *
 *     // Do the action
 *     purchaseItem()
 *
 *     // Show success
 *     click.temporaryDisplay("<green>Purchased!", 40)
 * }
 * ```
 */
class ClickContext(
    val event: InventoryClickEvent,
    val gui: ChestGui,
) {
    val player: Player get() = event.whoClicked as Player
    val playerUuid: UUID get() = player.uniqueId
    val item: ItemStack? get() = event.currentItem
    val guiItem: GuiItem? get() = item?.let { GuiItems.create(it) {} }

    /**
     * Check if player is on cooldown. Returns true if on cooldown.
     */
    fun isOnCooldown(key: String): Boolean = CooldownManager.cooldown(playerUuid, key) > 0

    /**
     * Execute block if on cooldown, otherwise add cooldown.
     */
    inline fun ifOnCooldown(
        key: String,
        ticks: Long,
        onCooldown: () -> Unit,
    ): Boolean {
        if (isOnCooldown(key)) {
            onCooldown()
            return true
        }
        CooldownManager.addCooldown(playerUuid, key, ticks)
        return false
    }

    /**
     * Standard GUI cooldown check with temporary display.
     */
    fun cooldownCheck(ticks: Long = 10): Boolean {
        val cooldown = CooldownManager.cooldown(playerUuid, "gui_click")
        if (cooldown > 0) {
            temporaryDisplay(
                "<red>Не кликайте так быстро!",
                null,
                cooldown,
            )
            return false
        }
        CooldownManager.addCooldown(playerUuid, "gui_click", ticks)
        return true
    }

    /**
     * Check if player has enough balance. Returns null if not enough.
     */
    fun requireBalance(amount: Double): Double? {
        val balance = EconomyModule.getEconomy()?.getBalance(player) ?: return null
        return if (balance >= amount) balance else null
    }

    /**
     * Check if player has permission. Returns null if no permission.
     */
    fun requirePermission(permission: String): Boolean? = if (player.hasPermission(permission)) true else null

    /**
     * Temporarily change the clicked item's display.
     */
    fun temporaryDisplay(
        display: String,
        lore: List<String>? = null,
        ticks: Long = 60,
    ) {
        item?.let {
            GuiUtils.temporaryChange(
                it,
                TextUtil.mm(display, true),
                lore?.map { line -> TextUtil.mm(line, true) },
                ticks,
                gui::update,
            )
        }
        gui.update()
    }

    /**
     * Temporarily change the clicked item's display with Component.
     */
    fun temporaryDisplay(
        display: Component,
        lore: List<Component>? = null,
        ticks: Long = 60,
    ) {
        item?.let {
            GuiUtils.temporaryChange(
                it,
                display,
                lore,
                ticks,
                gui::update,
            )
        }
        gui.update()
    }

    /**
     * Show error message temporarily.
     */
    fun errorDisplay(
        message: String,
        ticks: Long = 60,
    ) {
        temporaryDisplay("<red>$message", null, ticks)
    }

    /**
     * Close inventory.
     */
    fun close() {
        event.whoClicked.closeInventory()
    }

    /**
     * Run player command.
     */
    fun command(cmd: String) {
        player.performCommand(cmd)
    }

    /**
     * Refresh the GUI.
     */
    fun refresh() {
        gui.update()
    }
}

// ==================== GUI-Level Event Handlers ====================

/**
 * Extension functions for ChestGui to add event handlers.
 */

/**
 * Handle clicks on the top inventory (the GUI).
 */
fun ChestGui.onTopClick(handler: (InventoryClickEvent) -> Unit) {
    setOnTopClick(handler)
}

/**
 * Handle clicks on the bottom inventory (player inventory).
 */
fun ChestGui.onBottomClick(handler: (InventoryClickEvent) -> Unit) {
    setOnBottomClick(handler)
}

/**
 * Handle drag events on the top inventory.
 */
fun ChestGui.onTopDrag(handler: (InventoryDragEvent) -> Unit) {
    setOnTopDrag(handler)
}

/**
 * Handle GUI close.
 */
fun ChestGui.onClose(handler: () -> Unit) {
    setOnClose { handler() }
}

// ==================== GuiBuilder Extensions ====================

/**
 * Add top click handler at GUI level.
 */
fun GuiBuilder.onTopClick(handler: (InventoryClickEvent) -> Unit) {
    onBuild { gui -> gui.setOnTopClick(handler) }
}

/**
 * Add bottom click handler at GUI level.
 */
fun GuiBuilder.onBottomClick(handler: (InventoryClickEvent) -> Unit) {
    onBuild { gui -> gui.setOnBottomClick(handler) }
}

/**
 * Add top drag handler at GUI level.
 */
fun GuiBuilder.onTopDrag(handler: (InventoryDragEvent) -> Unit) {
    onBuild { gui -> gui.setOnTopDrag(handler) }
}

/**
 * Add close handler at GUI level.
 */
fun GuiBuilder.onClose(handler: () -> Unit) {
    onBuild { gui -> gui.setOnClose { handler() } }
}

// ==================== Item Amount Helpers ====================

/**
 * Calculate amount to take based on click type.
 * Right-click takes half, left-click takes all.
 */
fun InventoryClickEvent.takeAmount(totalAmount: Int): Int =
    if (isRightClick) {
        (totalAmount + 1) / 2 // Ceiling division for half
    } else {
        totalAmount
    }

/**
 * Check if cursor is empty.
 */
fun InventoryClickEvent.hasCursor(): Boolean = cursor.type != org.bukkit.Material.AIR

/**
 * Check if current item exists.
 */
fun InventoryClickEvent.hasCurrentItem(): Boolean = currentItem != null && currentItem?.type != org.bukkit.Material.AIR

// ==================== State Management ====================

/**
 * Simple state holder for GUI state management.
 *
 * Example:
 * ```kotlin
 * val state = GuiState(BoostType.MONEY)
 *
 * gui("Shop", 6, player) {
 *     // Use state.value
 *     pagination {
 *         items(getBoostsForType(state.value)) { ... }
 *     }
 *
 *     navBar {
 *         button(4) {
 *             display("Switch Type: ${state.value}")
 *             onClick {
 *                 state.value = state.value.next()
 *                 refresh()
 *             }
 *         }
 *     }
 * }
 * ```
 */
class GuiState<T>(
    initialValue: T,
) {
    var value: T = initialValue
    private val listeners = mutableListOf<(T) -> Unit>()

    fun onChange(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun set(newValue: T) {
        value = newValue
        listeners.forEach { it(newValue) }
    }
}

/**
 * Create a state holder.
 */
fun <T> guiState(initialValue: T): GuiState<T> = GuiState(initialValue)

// ==================== Economy Helpers ====================

/**
 * Check if player has enough money and withdraw if so.
 * Returns true if successful.
 */
fun Player.tryWithdraw(amount: Double): Boolean {
    val econ = EconomyModule.getEconomy() ?: return false
    return if (econ.has(this, amount)) {
        econ.withdrawPlayer(this, amount)
        true
    } else {
        false
    }
}

/**
 * Get player's balance.
 */
val Player.balance: Double
    get() = EconomyModule.getEconomy()?.getBalance(this) ?: 0.0

/**
 * Check if player has enough balance.
 */
fun Player.hasBalance(amount: Double): Boolean = EconomyModule.getEconomy()?.has(this, amount) ?: false
