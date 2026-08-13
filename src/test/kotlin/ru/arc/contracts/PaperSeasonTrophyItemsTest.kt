package ru.arc.contracts

import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import java.util.UUID

class PaperSeasonTrophyItemsTest : TestBase() {
    @Test
    fun `created trophy has exact identity and owner evidence`() {
        val owner = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val trophy = PaperSeasonTrophyItems.create("arc:road_revival/mines_core", owner)

        trophy.type shouldBe Material.ECHO_SHARD
        trophy.amount shouldBe 1
        PaperSeasonTrophyItems.identity(trophy) shouldBe "arc:road_revival/mines_core"
        PaperSeasonTrophyItems.owner(trophy) shouldBe owner
        PaperSeasonTrophyItems.isBoundTrophy(trophy) shouldBe true
    }

    @Test
    fun `bound trophy cannot enter a container or a sell command`() {
        val player = server.addPlayer("TrophyOwner")
        val trophy = PaperSeasonTrophyItems.create("arc:road_revival/mines_core", player.uniqueId)
        val listener = SeasonTrophyProtectionListener()
        val view = requireNotNull(player.openInventory(Bukkit.createInventory(null, 9)))
        view.setCursor(trophy)
        val containerClick =
            InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL,
            )

        listener.onInventoryClick(containerClick)

        containerClick.isCancelled shouldBe true
        player.inventory.setItemInMainHand(trophy)
        val sell = PlayerCommandPreprocessEvent(player, "/sell hand")
        listener.onSellCommand(sell)
        sell.isCancelled shouldBe true
    }
}
