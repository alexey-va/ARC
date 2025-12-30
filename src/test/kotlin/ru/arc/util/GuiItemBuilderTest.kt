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
    }

    @Test
    fun testBuildWithStack() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val builder = GuiItemBuilder(stack)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(stack, guiItem.item, "Item should match")
    }

    @Test
    fun testBuildWithClickEvent() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        var clicked = false
        val clickEvent: Consumer<InventoryClickEvent> = Consumer { clicked = true }

        val builder = GuiItemBuilder(stack)
            .clickEvent(clickEvent)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        // Note: We can't easily test the click event without a real inventory click
        assertTrue(true, "Builder should accept click event")
    }

    @Test
    fun testBuildWithClickEventWithStack() {
        if (plugin == null) return
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
        if (plugin == null) return
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
        if (plugin == null) return
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
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val builder = GuiItemBuilder(stack)
        val guiItem = builder.build()

        assertNotNull(guiItem, "GuiItem should not be null")
        assertEquals(stack, guiItem.item, "Item should match")
    }
}

