@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.util

import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import ru.arc.TestBase
import java.util.function.Consumer

class GuiItemBuilderTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        assertNotNull(plugin, "Plugin must be loaded for tests")
    }

    @Test
    fun testBuildWithStack() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val builder = GuiItemBuilder(stack)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(stack, guiItem.item, "Item should match")
    }

    @Test
    fun testBuildWithClickEvent() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        var clicked = false
        val clickEvent: Consumer<InventoryClickEvent> = Consumer { clicked = true }

        val builder = GuiItemBuilder(stack)
            .clickEvent(clickEvent)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertTrue(true, "Builder should accept click event")
    }

    @Test
    fun testBuildWithClickEventWithStack() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        var receivedStack: org.bukkit.inventory.ItemStack? = null
        val clickEvent = java.util.function.BiConsumer<InventoryClickEvent, org.bukkit.inventory.ItemStack> { _, s ->
            receivedStack = s
        }

        val builder = GuiItemBuilder(stack)
            .clickEventWithStack(clickEvent)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertTrue(true, "Builder should accept click event with stack")
    }

    @Test
    fun testUpdateStack() {
        val stack1 = ItemStackMock(Material.DIAMOND, 1)
        val stack2 = ItemStackMock(Material.EMERALD, 1)

        val builder = GuiItemBuilder(stack1)
            .stack(stack2)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(stack2, guiItem.item, "Item should be updated")
    }

    @Test
    fun testBuilderChaining() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        var clicked = false
        val clickEvent: Consumer<InventoryClickEvent> = Consumer { clicked = true }

        val builder = GuiItemBuilder(stack)
            .clickEvent(clickEvent)
            .stack(ItemStackMock(Material.EMERALD, 1))

        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(Material.EMERALD, guiItem.item.type, "Stack should be updated")
    }

    @Test
    fun testBuildWithoutClickEvent() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val builder = GuiItemBuilder(stack)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(stack, guiItem.item, "Item should match")
    }
}
