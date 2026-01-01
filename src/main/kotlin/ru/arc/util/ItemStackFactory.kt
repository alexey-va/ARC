package ru.arc.util

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Factory for creating ItemStack instances.
 * Can be replaced in tests to use mock implementations.
 */
object ItemStackFactory {

    /**
     * The factory function used to create ItemStack instances.
     * Default implementation creates real ItemStack objects.
     * In tests, this can be replaced with a mock implementation.
     */
    @JvmStatic
    var factory: (Material, Int) -> ItemStack = { material, amount ->
        ItemStack(material, amount)
    }

    /**
     * Creates an ItemStack with the specified material and amount.
     */
    @JvmStatic
    fun create(material: Material, amount: Int = 1): ItemStack = factory(material, amount)

    /**
     * Creates an ItemStack with the specified material (amount = 1).
     */
    @JvmStatic
    fun create(material: Material): ItemStack = factory(material, 1)

    /**
     * Resets the factory to default implementation.
     */
    @JvmStatic
    fun reset() {
        factory = { material, amount -> ItemStack(material, amount) }
    }
}

