package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.gui.ArcMenuConfiguration
import ru.arc.gui.ArcMenuSchema
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime

class InventorySnapshotMenuTest : FunSpec({
    test("shared inventory lifecycle cancels native movement and copies only one exact source stack per plain click") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            val snapshot = snapshotWith(39, ItemStack(Material.DIAMOND_HELMET))
            val config = ArcMenuConfiguration.loadResource(javaClass.classLoader)
            var copies = 0
            PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), config).use { runtime ->
                val session = runtime.open(viewer, ArcMenuSchema.INVENTORY_SNAPSHOT) {
                    PaperMenuContent(Component.text("Snapshot"), regions = mapOf(ArcMenuSchema.SNAPSHOT_SLOTS to listOf(
                        PaperMenuEntry(snapshot.item(39)!!, acceptedClicks = setOf(ClickType.LEFT), onClick = { context ->
                            SnapshotCopy.copy(viewer, context.player, viewer.openInventory.topInventory === context.session.inventory,
                                context.event.click, snapshot, 39) { copies++ }
                        }),
                    )))
                }
                fun click(slot: Int, type: ClickType, action: InventoryAction) = paper.callEvent(
                    InventoryClickEvent(viewer.openInventory, InventoryType.SlotType.CONTAINER, slot, type, action),
                )
                listOf(
                    ClickType.SHIFT_LEFT to InventoryAction.MOVE_TO_OTHER_INVENTORY,
                    ClickType.RIGHT to InventoryAction.PICKUP_HALF,
                    ClickType.NUMBER_KEY to InventoryAction.HOTBAR_SWAP,
                    ClickType.SWAP_OFFHAND to InventoryAction.HOTBAR_SWAP,
                    ClickType.DOUBLE_CLICK to InventoryAction.COLLECT_TO_CURSOR,
                    ClickType.DROP to InventoryAction.DROP_ONE_SLOT,
                    ClickType.CREATIVE to InventoryAction.CLONE_STACK,
                ).forEach { (type, action) -> click(0, type, action).isCancelled shouldBe true }
                click(54, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled shouldBe true
                paper.callEvent(InventoryDragEvent(viewer.openInventory, ItemStack.empty(), ItemStack(Material.STONE), false,
                    mapOf(0 to ItemStack(Material.STONE)))).isCancelled shouldBe true
                copies shouldBe 0
                val event = click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
                event.isCancelled shouldBe true
                copies shouldBe 1
                viewer.inventory.getItem(0) shouldBe snapshot.item(39)
                // InventoryFramework may decorate the preview with a session UUID; the source copy never carries it.
                viewer.inventory.getItem(0)!!.itemMeta.persistentDataContainer.keys shouldBe snapshot.item(39)!!.itemMeta.persistentDataContainer.keys
                paper.callEvent(event)
                copies shouldBe 1
                runtime.replace(config)
                session.isOpen shouldBe false
                paper.callEvent(event)
                copies shouldBe 1
            }
        }
    }
})
