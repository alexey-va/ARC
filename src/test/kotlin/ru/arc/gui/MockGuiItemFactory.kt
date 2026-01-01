package ru.arc.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.function.Consumer

/**
 * Mock implementation of GuiItemFactory for tests.
 * Uses Mockito to create mock GuiItems that don't trigger Paper ClassLoader checks.
 */
class MockGuiItemFactory : GuiItemFactory {

    private val createdItems = mutableListOf<GuiItem>()

    override fun create(item: ItemStack): GuiItem {
        return createMockGuiItem(item, null)
    }

    override fun create(item: ItemStack, action: Consumer<InventoryClickEvent>): GuiItem {
        return createMockGuiItem(item, action)
    }

    override fun create(material: Material): GuiItem {
        return createMockGuiItem(ItemStackMock(material, 1), null)
    }

    override fun create(material: Material, action: Consumer<InventoryClickEvent>): GuiItem {
        return createMockGuiItem(ItemStackMock(material, 1), action)
    }

    private fun createMockGuiItem(item: ItemStack, action: Consumer<InventoryClickEvent>?): GuiItem {
        // Create a Mockito mock that doesn't call real constructor
        val mockGuiItem = mock<GuiItem>(defaultAnswer = Mockito.RETURNS_DEFAULTS)

        // Configure mock behavior
        var visible = true
        whenever(mockGuiItem.item).thenReturn(item)
        whenever(mockGuiItem.isVisible).thenAnswer { visible }
        doAnswer { invocation ->
            visible = invocation.getArgument(0)
            null
        }.whenever(mockGuiItem).setVisible(any())

        createdItems.add(mockGuiItem)
        return mockGuiItem
    }

    fun getCreatedItems(): List<GuiItem> = createdItems.toList()

    fun clear() {
        createdItems.clear()
    }
}

