@file:Suppress("DEPRECATION")

package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Kotlin extensions for ItemStack and ItemMeta.
 *
 * Provides idiomatic Kotlin API for common item operations.
 */

// === Custom Model Data ===

/**
 * Gets the custom model data, or null if not set.
 */
val ItemStack.customModelDataOrNull: Int?
    get() = itemMeta?.customModelDataOrNull

/**
 * Gets the custom model data, or 0 if not set.
 */
val ItemStack.customModelDataOrZero: Int
    get() = customModelDataOrNull ?: 0

/**
 * Gets the custom model data, or null if not set.
 */
val ItemMeta.customModelDataOrNull: Int?
    get() = if (hasCustomModelDataSafe()) customModelData else null

/**
 * Checks if the item has custom model data.
 * Uses new API with fallback to deprecated for compatibility.
 */
fun ItemMeta.hasCustomModelDataSafe(): Boolean =
    try {
        hasCustomModelDataComponent()
    } catch (_: Exception) {
        // Fallback for older versions or MockBukkit
        hasCustomModelData()
    }

/**
 * Checks if the item stack has custom model data.
 */
fun ItemStack.hasCustomModelDataSafe(): Boolean = itemMeta?.hasCustomModelDataSafe() ?: false

/**
 * Sets custom model data on the item.
 *
 * @param value the model data value, or null/0 to remove
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withCustomModelData(value: Int?): ItemStack {
    editMeta { meta ->
        if (value == null || value == 0) {
            meta.setCustomModelData(null)
        } else {
            meta.setCustomModelData(value)
        }
    }
    return this
}

// === Display Name ===

/**
 * Sets the display name using MiniMessage format.
 *
 * @param name the display name in MiniMessage format
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withDisplayName(name: String): ItemStack {
    editMeta { meta ->
        meta.displayName(TextUtil.mm(name))
    }
    return this
}

/**
 * Sets the display name as a Component.
 *
 * @param name the display name component
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withDisplayName(name: Component): ItemStack {
    editMeta { meta ->
        meta.displayName(name)
    }
    return this
}

/**
 * Gets the display name as a plain string, or null if not set.
 */
val ItemStack.displayNamePlain: String?
    get() = itemMeta?.displayName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }

// === Lore ===

/**
 * Sets the lore using MiniMessage format.
 *
 * @param lines the lore lines in MiniMessage format
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withLore(vararg lines: String): ItemStack = withLore(lines.toList())

/**
 * Sets the lore using MiniMessage format.
 *
 * @param lines the lore lines in MiniMessage format
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withLore(lines: List<String>): ItemStack {
    editMeta { meta ->
        meta.lore(lines.map { TextUtil.mm(it) })
    }
    return this
}

/**
 * Sets the lore as Components.
 *
 * @param lines the lore line components
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withComponentLore(lines: List<Component>): ItemStack {
    editMeta { meta ->
        meta.lore(lines)
    }
    return this
}

/**
 * Appends lines to existing lore using MiniMessage format.
 *
 * @param lines the lines to append
 * @return the modified ItemStack for chaining
 */
fun ItemStack.appendLore(vararg lines: String): ItemStack = appendLore(lines.toList())

/**
 * Appends lines to existing lore using MiniMessage format.
 *
 * @param lines the lines to append
 * @return the modified ItemStack for chaining
 */
fun ItemStack.appendLore(lines: List<String>): ItemStack {
    editMeta { meta ->
        val existing = meta.lore() ?: mutableListOf()
        val newLines = lines.map { TextUtil.mm(it) }
        meta.lore(existing + newLines)
    }
    return this
}

// === Amount ===

/**
 * Sets the stack amount.
 *
 * @param amount the new amount
 * @return the modified ItemStack for chaining
 */
fun ItemStack.withAmount(amount: Int): ItemStack {
    this.amount = amount
    return this
}

// === Utility ===

/**
 * Creates a copy with modifications.
 *
 * @param block the modification block
 * @return a new modified ItemStack
 */
inline fun ItemStack.copy(block: ItemStack.() -> Unit = {}): ItemStack = clone().apply(block)

/**
 * Checks if the ItemStack is null or air.
 */
fun ItemStack?.isNullOrAir(): Boolean = this == null || this.type.isAir

/**
 * Checks if the ItemStack is not null and not air.
 */
fun ItemStack?.isNotNullOrAir(): Boolean = !isNullOrAir()

// === Factory ===
// Note: For comprehensive DSL, use itemStack() from ItemStackDsl.kt

/**
 * Creates an ItemStack with simple DSL-style configuration.
 *
 * @deprecated Use itemStack() from ItemStackDsl.kt for better DSL support.
 *
 * Example:
 * ```
 * val item = simpleItemStack(Material.DIAMOND_SWORD) {
 *     withDisplayName("<gold>Epic Sword")
 *     withLore("<gray>A legendary weapon", "<red>+10 Damage")
 *     withCustomModelData(1001)
 * }
 * ```
 */
