package ru.arc.bschests

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LootGuiTest : TestBase() {
    @Test
    fun `cloud loot claims to cursor without GUI metadata and permits chosen player slot`() {
        try {
            val player = server.addPlayer()
            val expected = ItemStack(Material.DIAMOND, 8)
            expected.editMeta { it.persistentDataContainer.set(NamespacedKey(plugin, "loot-origin"), PersistentDataType.STRING, "dungeon") }
            val rewards = listOf(expected, ItemStack(Material.IRON_INGOT, 12), ItemStack(Material.BREAD, 4), ItemStack(Material.TORCH, 16), ItemStack(Material.GOLDEN_APPLE))
            val loot = CustomLootData(playerUuid = UUID(0, 1), chestUuid = UUID(0, 42)).apply { fillIfEmpty(rewards) }
            LootGuiFactory.open(player, loot)
            val top = player.openInventory.topInventory
            top.size shouldBe 27
            val lootSlot = top.contents.indexOfFirst { it?.type == Material.DIAMOND }
            val debris = setOf(Material.COBWEB, Material.COBBLESTONE, Material.DIRT, Material.STICK)
            top.contents.filterNotNull().count { it.type in debris } shouldBe 4
            top.contents.count { it == null || it.type.isAir } shouldBe 18
            top.getItem(lootSlot) shouldBe expected
            Files.writeString(Path.of("build/loot-gui-preview.json"), top.contents.mapIndexedNotNull { slot, item ->
                item?.let { "{\"slot\":$slot,\"material\":\"${it.type}\",\"amount\":${it.amount}}" }
            }.joinToString(prefix = "[", postfix = "]"))
            fun click(slot: Int, type: ClickType, action: InventoryAction) =
                InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, type, action)
                    .also { server.pluginManager.callEvent(it) }
            val cobweb = top.contents.indexOfFirst { it?.type == Material.COBWEB }
            click(cobweb, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled shouldBe true
            click(lootSlot, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP).isCancelled shouldBe true
            click(lootSlot, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled shouldBe true
            loot.snapshotItems() shouldBe rewards
            click(lootSlot, ClickType.RIGHT, InventoryAction.PICKUP_HALF).isCancelled shouldBe true
            player.openInventory.cursor shouldBe expected.clone().also { it.amount = 4 }
            loot.snapshotItems()[0]?.amount shouldBe 4
            player.inventory.storageContents.filterNotNull() shouldBe emptyList()
            // MockBukkit dispatches the event, but does not execute native slot placement.
            val chosenSlot = top.size + 5
            click(chosenSlot, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled shouldBe false
            player.openInventory.setItem(chosenSlot, player.openInventory.cursor)
            player.openInventory.setCursor(null)
            click(lootSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled shouldBe true
            player.openInventory.cursor shouldBe expected.clone().also { it.amount = 4 }
            loot.snapshotItems() shouldBe listOf(null) + rewards.drop(1)
            top.getItem(lootSlot) shouldBe null
            val remainingSlots = top.contents.mapIndexedNotNull { slot, item -> item?.let { slot to it.clone() } }
            LootGuiFactory.open(player, loot)
            player.openInventory.topInventory.contents.mapIndexedNotNull { slot, item -> item?.let { slot to it } } shouldBe remainingSlots
        } finally { ArcMenus.close() }
    }

    @Test
    fun `full inventory still allows cursor pickup while shift transfers only available space`() {
        try {
            val player = server.addPlayer()
            val loot = CustomLootData().apply { fillIfEmpty(listOf(ItemStack(Material.DIAMOND, 8))) }
            LootGuiFactory.open(player, loot)
            val slot = player.openInventory.topInventory.contents.indexOfFirst { it?.type == Material.DIAMOND }
            player.inventory.storageContents = Array(36) { ItemStack(Material.STONE, 64) }
            fun click(type: ClickType, action: InventoryAction) = server.pluginManager.callEvent(
                InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, type, action),
            )
            click(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
            loot.snapshotItems()[0]?.amount shouldBe 8
            player.inventory.setItem(8, ItemStack(Material.DIAMOND, 60))
            click(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
            player.inventory.getItem(8)?.amount shouldBe 64
            loot.snapshotItems()[0]?.amount shouldBe 4
            click(ClickType.LEFT, InventoryAction.PICKUP_ALL)
            player.openInventory.cursor shouldBe ItemStack(Material.DIAMOND, 4)
            loot.isExhausted() shouldBe true
        } finally { ArcMenus.close() }
    }

    @Test
    fun `scatter preserves every source index and is stable across opens`() {
        for (count in listOf(0, 1, 9, 27, 28, 54)) {
            val slots = LootGuiFactory.scatteredSlots(count, 42)
            slots.sorted() shouldBe (0 until LootGuiFactory.calculateRows(count) * 9).toList()
            LootGuiFactory.scatteredSlots(count, 42) shouldBe slots
            (slots == slots.sorted()) shouldBe false
        }
        (LootGuiFactory.scatteredSlots(5, 1) == LootGuiFactory.scatteredSlots(5, 2)) shouldBe false
    }
}
