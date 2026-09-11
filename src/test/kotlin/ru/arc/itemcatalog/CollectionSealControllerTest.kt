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
})
