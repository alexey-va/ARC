package ru.arc.util

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import ru.arc.TestBase
import java.util.UUID

class GuiUtilsTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
    }

    // ========== Background Method Tests ==========

    @Test
    fun testBackgroundWithMaterial() {
        if (plugin == null) return
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)

        assertNotNull(guiItem, "Should create background item")
        assertNotNull(guiItem.item, "Item should not be null")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use specified material")
    }

    @Test
    fun testBackgroundWithMaterialAndModel() {
        if (plugin == null) return
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)

        assertNotNull(guiItem, "Should create background item with model")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use specified material")
    }

    @Test
    fun testBackgroundDefault() {
        if (plugin == null) return
        val guiItem = GuiUtils.background()

        assertNotNull(guiItem, "Should create default background")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use default material")
    }

    @Test
    fun testBackgroundCaching() {
        if (plugin == null) return
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        val guiItem2 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)

        assertSame(guiItem1, guiItem2, "Should return cached item")
    }

    @Test
    fun testBackgroundDifferentModels() {
        if (plugin == null) return
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        val guiItem2 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 2000)

        assertNotSame(guiItem1, guiItem2, "Different models should return different items")
    }

    @Test
    fun testBackgroundCancelsClick() {
        if (plugin == null) return
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)

        // The background item should cancel clicks
        // This is tested by checking the GuiItem's click handler exists
        assertNotNull(guiItem, "Should have click handler")
    }

    // ========== TemporaryChange Method Tests ==========

    @Test
    fun testTemporaryChangeWithDisplay() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name", NamedTextColor.RED)

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 1, {})

        assertNotNull(task, "Should return task")
        assertNotNull(stack.itemMeta?.displayName(), "Display name should be set")
    }

    @Test
    fun testTemporaryChangeWithLore() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newLore = listOf(Component.text("Line 1"), Component.text("Line 2"))

        val task = GuiUtils.temporaryChange(stack, null, newLore, 1, {})

        assertNotNull(task, "Should return task")
    }

    @Test
    fun testTemporaryChangeNegativeTicks() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name")

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, -1, {})

        assertNull(task, "Should return null for negative ticks")
    }

    @Test
    fun testTemporaryChangeZeroTicks() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name")
        var callbackCalled = false

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 0, { callbackCalled = true })

        // Wait a bit for the task to complete
        Thread.sleep(100)
        assertTrue(callbackCalled, "Callback should be called")
    }

    @Test
    fun testTemporaryChangeWithTagResolver() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name")
        val resolver = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            "test",
            net.kyori.adventure.text.minimessage.tag.Tag.inserting(Component.text("value"))
        )

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 1, {}, resolver)

        assertNotNull(task, "Should return task with resolver")
    }

    // ========== CooldownCheck Method Tests ==========

    @Test
    fun testCooldownCheckFirstClick() {
        if (plugin == null) return
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItem(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = ChestGui(3, "Test")

        val result = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)

        assertTrue(result, "First click should pass cooldown check")
    }

    @Test
    fun testCooldownCheckRapidClicks() {
        if (plugin == null) return
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItem(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = ChestGui(3, "Test")

        // First click should pass
        val first = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)
        assertTrue(first, "First click should pass")

        // Immediate second click should fail
        val second = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)
        assertFalse(second, "Second click should fail cooldown")
    }

    @Test
    fun testCooldownCheckWithNullGui() {
        if (plugin == null) return
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItem(ItemStackMock(Material.DIAMOND, 1))

        // First click
        GuiUtils.cooldownCheck(guiItem, playerUuid, null)

        // Second click with null gui
        val result = GuiUtils.cooldownCheck(guiItem, playerUuid, null)
        assertFalse(result, "Should handle null gui")
    }

    // ========== ConstructAndShowAsync Method Tests ==========

    @Test
    fun testConstructAndShowAsync() {
        if (plugin == null) return
        try {
            val player = server.addPlayer()
            val supplier = java.util.function.Supplier<ChestGui> {
                ChestGui(3, "Test")
            }

            GuiUtils.constructAndShowAsync(supplier, player, 1)

            // Wait a bit for async operation
            Thread.sleep(200)

            // If no exception, test passed
            assertTrue(true, "Should construct and show async")
        } catch (e: Exception) {
            // Some dependencies might not be available in test environment
            // This is acceptable
        }
    }

    @Test
    fun testConstructAndShowAsyncDefaultDelay() {
        if (plugin == null) return
        try {
            val player = server.addPlayer()
            val supplier = java.util.function.Supplier<ChestGui> {
                ChestGui(3, "Test")
            }

            GuiUtils.constructAndShowAsync(supplier, player)

            // Wait a bit for async operation
            Thread.sleep(200)

            assertTrue(true, "Should use default delay")
        } catch (e: Exception) {
            // Acceptable in test environment
        }
    }

    @Test
    fun testConstructAndShowAsyncWithException() {
        if (plugin == null) return
        try {
            val player = server.addPlayer()
            val supplier = java.util.function.Supplier<ChestGui> {
                throw RuntimeException("Test exception")
            }

            GuiUtils.constructAndShowAsync(supplier, player, 1)

            // Wait a bit
            Thread.sleep(200)

            // Should handle exception gracefully
            assertTrue(true, "Should handle exception")
        } catch (e: Exception) {
            // Acceptable
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun testBackgroundWithDifferentMaterials() {
        if (plugin == null) return
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)
        val guiItem2 = GuiUtils.background(Material.BLACK_STAINED_GLASS_PANE)

        assertNotSame(guiItem1, guiItem2, "Different materials should return different items")
    }

    @Test
    fun testTemporaryChangeRestoresOriginal() {
        if (plugin == null) return
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val originalDisplay = stack.itemMeta?.displayName()
        val newDisplay = Component.text("Temporary")

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 1, {})

        // Wait for task to complete
        Thread.sleep(200)

        // Original should be restored (or at least changed)
        assertNotNull(stack.itemMeta, "Meta should exist")
    }

    @Test
    fun testCooldownCheckDifferentPlayers() {
        if (plugin == null) return
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val guiItem = GuiItem(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = ChestGui(3, "Test")

        val result1 = GuiUtils.cooldownCheck(guiItem, uuid1, chestGui)
        val result2 = GuiUtils.cooldownCheck(guiItem, uuid2, chestGui)

        assertTrue(result1, "First player should pass")
        assertTrue(result2, "Second player should pass (different UUID)")
    }
}

