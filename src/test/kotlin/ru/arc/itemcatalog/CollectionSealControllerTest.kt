package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.gui.ArcMenus
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.ClickType
import java.nio.file.Files
import java.util.Comparator

private fun testSettings(): RewardCatalogSettings =
    RewardCatalogSettings(
        enabled = true,
        title = "Каталог",
        categories = listOf(
            RewardCatalogCategory(
                id = "set_frost",
                name = "Морозный комплект",
                description = emptyList(),
                icon = CatalogIconStyle(Material.PAPER.name),
                entries = emptyList(),
            ),
        ),
        messages = RewardCatalogMessages.DEFAULT,
    )

class CollectionSealControllerTest : StringSpec({
    "cancelled air and block clicks reach the seal handler in main hand" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("SealHolder")
            val controller = CollectionSealController(paper.createSimplePlugin("SealTest"), testSettings())
            val seal = CollectionSealIdentity.mark(ItemStack(Material.PAPER), "set_frost")
            controller.register()

            try {
                for (action in listOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
                    val event =
                        PlayerInteractEvent(
                            player,
                            action,
                            seal,
                            null,
                            BlockFace.SELF,
                            EquipmentSlot.HAND,
                        ).also { it.isCancelled = true }

                    paper.callEvent(event)

                    event.isCancelled shouldBe true
                    player.nextComponentMessage().shouldNotBeNull()
                }

                val offHand =
                    PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_AIR,
                        seal,
                        null,
                        BlockFace.SELF,
                        EquipmentSlot.OFF_HAND,
                    ).also { it.isCancelled = true }
                paper.callEvent(offHand)
                player.nextComponentMessage().shouldBeNull()

                controller.close()
                val afterClose =
                    PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_AIR,
                        seal,
                        null,
                        BlockFace.SELF,
                        EquipmentSlot.HAND,
                    ).also { it.isCancelled = true }
                paper.callEvent(afterClose)
                player.nextComponentMessage().shouldBeNull()
            } finally {
                controller.close()
            }
        }
    }

    "archived set markers resolve detached choices after the live category is gone" {
        MockBukkitTestRuntime.open().use { paper ->
            val categoryId = "set_frozen_${"a".repeat(64)}"
            val snapshot = ArchivedCollectionSeal(
                categoryId = categoryId,
                name = "Старый комплект",
                description = listOf("Состав зафиксирован при выдаче."),
                choices = listOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD)),
            )
            val settings = RewardCatalogSettings(
                enabled = true,
                title = "Каталог",
                categories = emptyList(),
                messages = RewardCatalogMessages.DEFAULT,
            )
            val player = paper.addPlayer("ArchivedSealHolder")
            val plugin = paper.createSimplePlugin("ArchivedSealTest")
            val root = Files.createTempDirectory("archived-seal-menu")
            ArcMenus.initialize(plugin, root)
            val controller = CollectionSealController(
                plugin,
                settings,
                archivedSeal = { id -> snapshot.takeIf { it.categoryId == id } },
            )
            controller.register()
            try {
                val seal = CollectionSealIdentity.mark(ItemStack(Material.PAPER), categoryId)
                player.inventory.setItemInMainHand(seal)
                val event = PlayerInteractEvent(
                    player,
                    Action.RIGHT_CLICK_AIR,
                    seal,
                    null,
                    BlockFace.SELF,
                    EquipmentSlot.HAND,
                ).also { it.isCancelled = true }
                paper.callEvent(event)

                event.isCancelled shouldBe true
                player.nextComponentMessage().shouldBeNull()
                fun chooseDiamond() {
                    val view = player.openInventory
                    val slot = view.topInventory.contents.indexOfFirst { it?.type == Material.DIAMOND }
                    (slot >= 0) shouldBe true
                    paper.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                        slot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    paper.performTicks(1)
                }
                chooseDiamond()
                chooseDiamond()
                player.inventory.contents.filterNotNull().sumOf { if (it.type == Material.DIAMOND) it.amount else 0 } shouldBe 1
                player.inventory.contents.filterNotNull().sumOf { if (it.type == Material.EMERALD) it.amount else 0 } shouldBe 0
                player.inventory.contents.filterNotNull().count { CollectionSealIdentity.categoryId(it) == categoryId } shouldBe 0
            } finally {
                controller.close()
                ArcMenus.close()
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
})
