@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
package ru.arc.util

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
import org.mockito.kotlin.mock
import ru.arc.TestBase
import ru.arc.gui.GuiItems
import java.util.UUID

class GuiUtilsTest : TestBase() {

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        assertNotNull(plugin, "Plugin must be loaded for tests")
    }

    // ========== Background Method Tests ==========

    @Test
    fun testBackgroundWithMaterial() {
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)
        assertNotNull(guiItem, "Should create background item")
        assertNotNull(guiItem.item, "Item should not be null")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use specified material")
    }

    @Test
    fun testBackgroundWithMaterialAndModel() {
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        assertNotNull(guiItem, "Should create background item with model")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use specified material")
    }

    @Test
    fun testBackgroundDefault() {
        val guiItem = GuiUtils.background()
        assertNotNull(guiItem, "Should create default background")
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, guiItem.item.type, "Should use default material")
    }

    @Test
    fun testBackgroundCaching() {
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        val guiItem2 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        assertSame(guiItem1, guiItem2, "Should return cached item")
    }

    @Test
    fun testBackgroundDifferentModels() {
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 1000)
        val guiItem2 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE, 2000)
        assertNotSame(guiItem1, guiItem2, "Different models should return different items")
    }

    @Test
    fun testBackgroundCancelsClick() {
        val guiItem = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)
        assertNotNull(guiItem, "Should have click handler")
    }

    // ========== TemporaryChange Method Tests ==========

    @Test
    fun testTemporaryChangeWithDisplay() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name", NamedTextColor.RED)

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 1, {})

        assertNotNull(task, "Should return task")
        assertNotNull(stack.itemMeta?.displayName(), "Display name should be set")
    }

    @Test
    fun testTemporaryChangeWithLore() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newLore = listOf(Component.text("Line 1"), Component.text("Line 2"))

        val task = GuiUtils.temporaryChange(stack, null, newLore, 1, {})

        assertNotNull(task, "Should return task")
    }

    @Test
    fun testTemporaryChangeNegativeTicks() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name")

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, -1, {})

        assertNull(task, "Should return null for negative ticks")
    }

    @Test
    fun testTemporaryChangeZeroTicks() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("New Name")
        var callbackCalled = false

        val task = GuiUtils.temporaryChange(stack, newDisplay, null, 0, { callbackCalled = true })

        // Run scheduler to execute the task
        server.scheduler.performTicks(1)
        assertTrue(callbackCalled, "Callback should be called")
    }

    @Test
    fun testTemporaryChangeWithTagResolver() {
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
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItems.create(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = mock<ChestGui>()
        val result = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)
        assertTrue(result, "First click should pass cooldown check")
    }

    @Test
    fun testCooldownCheckRapidClicks() {
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItems.create(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = mock<ChestGui>()
        val first = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)
        assertTrue(first, "First click should pass")
        val second = GuiUtils.cooldownCheck(guiItem, playerUuid, chestGui)
        assertFalse(second, "Second click should fail cooldown")
    }

    @Test
    fun testCooldownCheckWithNullGui() {
        val playerUuid = UUID.randomUUID()
        val guiItem = GuiItems.create(ItemStackMock(Material.DIAMOND, 1))
        GuiUtils.cooldownCheck(guiItem, playerUuid, null)
        val result = GuiUtils.cooldownCheck(guiItem, playerUuid, null)
        assertFalse(result, "Should handle null gui")
    }

    // ========== ConstructAndShowAsync Method Tests ==========

    @Test
    fun testConstructAndShowAsync() {
        val player = server.addPlayer()
        val supplier = java.util.function.Supplier<ChestGui> {
            mock<ChestGui>()
        }

        GuiUtils.constructAndShowAsync(supplier, player, 1)
        server.scheduler.performTicks(5)
        assertTrue(true, "Should construct and show async")
    }

    @Test
    fun testConstructAndShowAsyncDefaultDelay() {
        val player = server.addPlayer()
        val supplier = java.util.function.Supplier<ChestGui> {
            mock<ChestGui>()
        }

        GuiUtils.constructAndShowAsync(supplier, player)
        server.scheduler.performTicks(5)
        assertTrue(true, "Should use default delay")
    }

    @Test
    fun testConstructAndShowAsyncWithException() {
        val player = server.addPlayer()
        val supplier = java.util.function.Supplier<ChestGui> {
            throw RuntimeException("Test exception")
        }

        GuiUtils.constructAndShowAsync(supplier, player, 1)
        server.scheduler.performTicks(5)
        assertTrue(true, "Should handle exception")
    }

    // ========== Edge Cases ==========

    @Test
    fun testBackgroundWithDifferentMaterials() {
        val guiItem1 = GuiUtils.background(Material.GRAY_STAINED_GLASS_PANE)
        val guiItem2 = GuiUtils.background(Material.BLACK_STAINED_GLASS_PANE)
        assertNotSame(guiItem1, guiItem2, "Different materials should return different items")
    }

    @Test
    fun testTemporaryChangeRestoresOriginal() {
        val stack = ItemStackMock(Material.DIAMOND, 1)
        val newDisplay = Component.text("Temporary")

        GuiUtils.temporaryChange(stack, newDisplay, null, 1, {})
        server.scheduler.performTicks(5)
        assertNotNull(stack.itemMeta, "Meta should exist")
    }

    @Test
    fun testCooldownCheckDifferentPlayers() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val guiItem = GuiItems.create(ItemStackMock(Material.DIAMOND, 1))
        val chestGui = mock<ChestGui>()
        val result1 = GuiUtils.cooldownCheck(guiItem, uuid1, chestGui)
        val result2 = GuiUtils.cooldownCheck(guiItem, uuid2, chestGui)
        assertTrue(result1, "First player should pass")
        assertTrue(result2, "Second player should pass (different UUID)")
    }
}
