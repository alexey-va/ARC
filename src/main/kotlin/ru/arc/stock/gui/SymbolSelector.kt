package ru.arc.stock.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.configs.StockConfig
import ru.arc.stock.Stock
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.ItemStackBuilder
import ru.arc.util.TextUtil

class SymbolSelector(private val stockPlayer: StockPlayer) : ChestGui(
    4,
    TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("symbol-selector.menu-title")))
) {
    private lateinit var back: GuiItem
    private lateinit var profile: GuiItem
    private lateinit var all: GuiItem
    private lateinit var market: GuiItem
    private val rows = 4

    init {
        setRows(rows)
        setupBackground()
        setupStocks()
        setupNav()
    }

    private fun setupStocks() {
        val paginatedPane = PaginatedPane(9, rows - 2)
        val guiItemList = StockMarket.stocks()
            .filter { it.price > 0.0 }
            .filter { StockMarket.isEnabledStock(it) }
            .sortedBy { if (it.type == Stock.Type.STOCK) 0 else 1 }
            .map { stockItem(it) }
        paginatedPane.populateWithGuiItems(guiItemList)
        this.addPane(Slot.fromXY(0, 1), paginatedPane)
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, rows - 1), pane)
        val tagResolver = stockPlayer.tagResolver()

        back = ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
            .display(StockConfig.string("symbol-selector.back-display"))
            .tagResolver(tagResolver)
            .lore(StockConfig.stringList("symbol-selector.back-lore"))
            .modelData(11013)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                (click.whoClicked as Player).performCommand(StockConfig.mainMenuBackCommand)
            }.build()
        pane.addItem(back, 0, 0)

        all = ItemStackBuilder(Material.GREEN_STAINED_GLASS_PANE)
            .tagResolver(tagResolver)
            .display(StockConfig.string("symbol-selector.all-positions-display"))
            .lore(StockConfig.stringList("symbol-selector.all-positions-lore"))
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, null) }, click.whoClicked)
            }.build()
        pane.addItem(all, 4, 0)

        profile = ItemStackBuilder(Material.PLAYER_HEAD)
            .skull(stockPlayer.playerUuid)
            .tagResolver(tagResolver)
            .display(StockConfig.string("symbol-selector.profile-display"))
            .lore(StockConfig.stringList("symbol-selector.profile-lore"))
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ ProfileMenu(stockPlayer, 0, null) }, click.whoClicked)
            }.build()
        pane.addItem(profile, 8, 0)

        val topNavigation = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 0), topNavigation)
        market = ItemStackBuilder(Material.BELL)
            .display(StockConfig.string("symbol-selector.market-display"))
            .lore(StockConfig.stringList("symbol-selector.market-lore"))
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                val p = click.whoClicked as Player
                p.performCommand(StockConfig.string("market-command"))
                p.closeInventory()
            }.build()
        topNavigation.addItem(market, 4, 0)
    }

    private fun stockItem(stock: Stock): GuiItem {
        val positions = stockPlayer.positions(stock.symbol)
        val size = positions?.size ?: 0
        return ItemStackBuilder(stock.icon?.stack() ?: org.bukkit.inventory.ItemStack(Material.BARRIER))
            .display(stock.display)
            .lore(stock.lore)
            .tagResolver(stock.tagResolver())
            .appendResolver("positions_in_symbol", size.toString())
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, stock.symbol) }, click.whoClicked)
            }.build()
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, rows, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)

        val pane2 = OutlinePane(9, 2, Pane.Priority.LOW)
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE))
        pane2.setRepeat(true)
        this.addPane(Slot.fromXY(0, 1), pane2)
    }
}
