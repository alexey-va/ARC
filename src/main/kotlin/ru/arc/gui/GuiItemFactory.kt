package ru.arc.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.util.ItemStackFactory
import java.util.function.Consumer

/**
 * Factory interface for creating GuiItem instances.
 * Can be replaced in tests with a mock implementation.
 */
interface GuiItemFactory {
    fun create(item: ItemStack): GuiItem
    fun create(item: ItemStack, action: Consumer<InventoryClickEvent>): GuiItem
    fun create(material: Material): GuiItem
    fun create(material: Material, action: Consumer<InventoryClickEvent>): GuiItem
}

/**
 * Default implementation that creates real GuiItem instances.
 */
class DefaultGuiItemFactory : GuiItemFactory {
    override fun create(item: ItemStack): GuiItem = GuiItem(item)

    override fun create(item: ItemStack, action: Consumer<InventoryClickEvent>): GuiItem =
        GuiItem(item, action)

    override fun create(material: Material): GuiItem =
        GuiItem(ItemStackFactory.create(material))

    override fun create(material: Material, action: Consumer<InventoryClickEvent>): GuiItem =
        GuiItem(ItemStackFactory.create(material), action)
}

/**
 * Global registry for the GuiItem factory.
 * Set to mock implementation in tests.
 */
object GuiItems {
    @JvmStatic
    var factory: GuiItemFactory = DefaultGuiItemFactory()

    @JvmStatic
    fun create(item: ItemStack): GuiItem = factory.create(item)

    @JvmStatic
    fun create(item: ItemStack, action: Consumer<InventoryClickEvent>): GuiItem =
        factory.create(item, action)

    @JvmStatic
    fun create(material: Material): GuiItem = factory.create(material)

    @JvmStatic
    fun create(material: Material, action: Consumer<InventoryClickEvent>): GuiItem =
        factory.create(material, action)

    @JvmStatic
    fun reset() {
        factory = DefaultGuiItemFactory()
    }
}


