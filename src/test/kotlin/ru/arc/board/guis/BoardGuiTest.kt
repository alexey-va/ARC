package ru.arc.board.guis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.board.BoardEntryData
import ru.arc.board.BoardEntryType
import ru.arc.board.BoardItem
import ru.arc.board.BoardManager
import ru.arc.board.ContractBoardCards
import ru.arc.board.ItemIcon
import ru.arc.config.BoardConfig
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenus
import ru.arc.util.TextUtil
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class BoardGuiTest : StringSpec({
    "board leaves empty slots clear, pages player entries, and uses semantic controls" {
        ConfigManager.clear()
        ArcMenus.resetForTests()
        var items = emptyList<BoardItem>()
        mockkStatic(BoardManager::class)
        mockkObject(ContractBoardCards)
        every { BoardManager.items() } answers { items }
        every { ContractBoardCards.current() } returns emptyList()

        try {
            MockBukkitTestRuntime.open().use { paper ->
                paper.loadSimplePlugin(BoardMenuTestPlugin::class.java)
                val player = paper.addPlayer("BoardViewer")

                BoardGuiFactory.open(player)
                val emptyInventory = player.openInventory.topInventory
                (0..44).all { emptyInventory.getItem(it) == null } shouldBe true

                items = (1..46).map(::boardItem)
                BoardGuiFactory.open(player)
                val inventory = player.openInventory.topInventory
                inventory.size shouldBe 54
                displayName(inventory.getItem(0)) shouldBe "Entry 1"
                displayName(inventory.getItem(44)) shouldBe "Entry 45"
                assertCustomModel(inventory, 45, Material.BLUE_STAINED_GLASS_PANE, 11001)
                assertCustomModel(inventory, 48, Material.BLUE_STAINED_GLASS_PANE, 11009)
                assertCustomModel(inventory, 50, Material.BLUE_STAINED_GLASS_PANE, 11008)
                inventory.getItem(53)!!.apply {
                    type shouldBe Material.WRITABLE_BOOK
                    itemMeta.hasCustomModelData() shouldBe false
                }

                paper.callEvent(click(player.openInventory, 50))
                (player.openInventory.topInventory === inventory) shouldBe true
                displayName(inventory.getItem(0)) shouldBe "Entry 46"
                (1..44).all { inventory.getItem(it) == null } shouldBe true

                paper.callEvent(click(player.openInventory, 48))
                (player.openInventory.topInventory === inventory) shouldBe true
                displayName(inventory.getItem(0)) shouldBe "Entry 1"
                displayName(inventory.getItem(44)) shouldBe "Entry 45"

                listOf(
                    BoardConfig.createEntryGuiName,
                    BoardConfig.editEntryGuiName,
                    BoardConfig.boardGuiName,
                    BoardConfig.rateGuiName,
                ).forEach { title ->
                    title.contains("&8") shouldBe false
                    val parsed = TextUtil.mm(title, true)
                    parsed.color() shouldBe NamedTextColor.DARK_GRAY
                    PlainTextComponentSerializer.plainText().serialize(parsed).contains("&8") shouldBe false
                }
            }
        } finally {
            ArcMenus.resetForTests()
            ARC.plugin = null
            ConfigManager.clear()
            unmockkObject(ContractBoardCards)
            unmockkStatic(BoardManager::class)
        }
    }
})

internal open class BoardMenuTestPlugin : ARC() {
    override fun onLoad() {
        ARC.plugin = this
    }

    override fun onEnable() {
        ArcMenus.initialize(this, dataFolder.toPath())
    }

    override fun onDisable() {
        ArcMenus.close()
        ARC.plugin = null
    }
}

private fun boardItem(index: Int): BoardItem {
    val stack = ItemStack(Material.PAPER)
    val meta = stack.itemMeta
    meta.displayName(Component.text("Entry $index"))
    stack.itemMeta = meta
    return BoardItem(
        entry = BoardEntryData(
            entryUuid = UUID.randomUUID(),
            playerUuid = UUID.randomUUID(),
            playerName = "Builder",
            type = BoardEntryType.SELL,
            text = "Description $index",
            title = "Entry $index",
            icon = ItemIcon.of(Material.PAPER, 0),
            color = BarColor.BLUE,
        ),
        stack = stack,
    )
}

private fun assertCustomModel(inventory: Inventory, slot: Int, material: Material, modelData: Int) {
    val item = inventory.getItem(slot)!!
    item.type shouldBe material
    item.itemMeta.hasCustomModelData() shouldBe true
    item.itemMeta.customModelData shouldBe modelData
}

private fun displayName(item: ItemStack?): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(requireNotNull(item).itemMeta.displayName()))

private fun click(view: org.bukkit.inventory.InventoryView, slot: Int) =
    InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL)
