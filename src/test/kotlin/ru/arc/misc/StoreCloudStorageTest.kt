package ru.arc.misc

import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.gui.ArcMenus
import ru.arc.store.StoreData

class StoreCloudStorageTest : TestBase() {
    @Test
    fun `store deposits and withdraws through cursor and rejects forbidden items unchanged`() {
        try {
            val player = server.addPlayer()
            val store = StoreData(player.uniqueId)
            StoreGuiFactory.open(player, store)
            val view = player.openInventory
            view.topInventory.size shouldBe 18
            fun click(slot: Int, action: InventoryAction) =
                InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, action)
                    .also { server.pluginManager.callEvent(it) }
            view.setCursor(ItemStack(Material.DIAMOND, 8))
            click(4, InventoryAction.PLACE_ALL).isCancelled shouldBe true
            view.cursor.type.isAir shouldBe true
            store.getSlots()[4] shouldBe ItemStack(Material.DIAMOND, 8)
            click(4, InventoryAction.PICKUP_ALL)
            view.cursor shouldBe ItemStack(Material.DIAMOND, 8)
            store.getSlots().all { it == null } shouldBe true
            click(view.topInventory.size + 7, InventoryAction.PLACE_ALL).isCancelled shouldBe false
            view.setCursor(ItemStack(Material.SHULKER_BOX))
            click(4, InventoryAction.PLACE_ALL)
            view.cursor shouldBe ItemStack(Material.SHULKER_BOX)
            store.getSlots().all { it == null } shouldBe true
        } finally { ArcMenus.close() }
    }
}
