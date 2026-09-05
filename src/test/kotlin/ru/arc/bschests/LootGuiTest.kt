package ru.arc.bschests

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.gui.ArcMenus

class LootGuiTest : TestBase() {
    @Test
    fun `rendered loot with framework UUID can be claimed without leaking metadata`() {
        val player = server.addPlayer()
        val expected = ItemStack(Material.DIAMOND, 8)
        expected.editMeta { it.persistentDataContainer.set(NamespacedKey(plugin, "loot-origin"), PersistentDataType.STRING, "dungeon") }
        val loot = CustomLootData().apply { fillIfEmpty(listOf(expected)) }
        val displayed = GuiItem(expected.clone(), plugin).also { it.applyUUID() }.item
        displayed.isSimilar(expected) shouldBe false
        val inventory = server.createInventory(null, 27)
        inventory.setItem(12, displayed)
        player.openInventory(inventory)
        val event = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 12, ClickType.LEFT, InventoryAction.PICKUP_ALL)

        LootGuiFactory.takeLoot(event, loot, expected, 0) shouldBe true

        loot.isExhausted() shouldBe true
        event.isCancelled shouldBe true
        event.currentItem shouldBe null
        player.inventory.storageContents.filterNotNull() shouldBe listOf(expected)
        player.inventory.storageContents.filterNotNull().filter { it.type == Material.DIAMOND }.all { it.isSimilar(expected) } shouldBe true
        LootGuiFactory.takeLoot(event, loot, expected, 0) shouldBe false
    }

    @Test
    fun `full and partially full storage cannot consume a complete reward`() {
        val player = server.addPlayer()
        val expected = ItemStack(Material.DIAMOND, 8)
        val loot = CustomLootData().apply { fillIfEmpty(listOf(expected)) }
        val inventory = server.createInventory(null, 27)
        inventory.setItem(12, GuiItem(expected.clone(), plugin).also { it.applyUUID() }.item)
        player.openInventory(inventory)
        val event = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 12, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
        player.inventory.storageContents = Array(36) { ItemStack(Material.STONE, 64) }

        LootGuiFactory.takeLoot(event, loot, expected, 0) shouldBe false
        player.inventory.setItem(0, ItemStack(Material.DIAMOND, 60))
        LootGuiFactory.takeLoot(event, loot, expected, 0) shouldBe false
        loot.snapshotItems() shouldBe listOf(expected)
        player.inventory.setItem(0, ItemStack(Material.DIAMOND, 56))
        LootGuiFactory.takeLoot(event, loot, expected, 0) shouldBe true
        player.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 64)
        loot.isExhausted() shouldBe true
        event.isCancelled shouldBe true
        event.currentItem shouldBe null
        player.inventory.storageContents.filterNotNull().filter { it.type == Material.DIAMOND }.all { it.isSimilar(expected) } shouldBe true
    }

    @Test
    fun `menu dispatch claims scattered loot and blocks decorations and unsafe clicks`() {
        try {
            val player = server.addPlayer()
            val expected = ItemStack(Material.DIAMOND, 8)
            val rewards = listOf(expected, ItemStack(Material.IRON_INGOT, 12), ItemStack(Material.BREAD, 4), ItemStack(Material.TORCH, 16), ItemStack(Material.GOLDEN_APPLE))
            val loot = CustomLootData().apply { fillIfEmpty(rewards) }
            LootGuiFactory.open(player, loot)
            val top = player.openInventory.topInventory
            top.size shouldBe 27
            val lootSlot = top.contents.indexOfFirst { it?.type == Material.DIAMOND }
            val cobwebs = top.contents.indices.filter { top.getItem(it)?.type == Material.COBWEB }
            cobwebs.size shouldBe 2
            java.nio.file.Files.writeString(
                java.nio.file.Path.of("build/loot-gui-preview.json"),
                top.contents.mapIndexedNotNull { slot, item ->
                    item?.let { "{\"slot\":$slot,\"material\":\"${it.type}\",\"amount\":${it.amount}}" }
                }.joinToString(prefix = "[", postfix = "]"),
            )
            val changedMeta = top.getItem(lootSlot)!!.clone().also { it.amount = 9 }
            top.setItem(lootSlot, changedMeta)
            val stale = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, lootSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL)
            LootGuiFactory.takeLoot(stale, loot, expected, 0) shouldBe false
            top.setItem(lootSlot, changedMeta.also { it.amount = 8 })
            fun click(slot: Int, type: ClickType, action: InventoryAction): InventoryClickEvent =
                InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, type, action)
                    .also { server.pluginManager.callEvent(it) }
            click(cobwebs.first(), ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled shouldBe true
            click(lootSlot, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP).isCancelled shouldBe true
            click(lootSlot, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled shouldBe true
            loot.isExhausted() shouldBe false
            click(lootSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled shouldBe true
            loot.snapshotItems() shouldBe listOf(null) + rewards.drop(1)
            top.getItem(lootSlot) shouldBe null
            player.inventory.storageContents.filterNotNull() shouldBe listOf(expected)
            click(lootSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled shouldBe true
        } finally {
            ArcMenus.close()
        }
    }

    @Test
    fun `scatter preserves every source index and stays stable when rewards are removed`() {
        for (count in listOf(0, 1, 9, 27, 28, 54)) {
            val slots = LootGuiFactory.scatteredSlots(count, 42)
            slots.sorted() shouldBe (0 until LootGuiFactory.calculateRows(count) * 9).toList()
            LootGuiFactory.scatteredSlots(count, 42) shouldBe slots
            (slots == slots.sorted()) shouldBe false
        }
        (LootGuiFactory.scatteredSlots(5, 1) == LootGuiFactory.scatteredSlots(5, 2)) shouldBe false
    }
}
