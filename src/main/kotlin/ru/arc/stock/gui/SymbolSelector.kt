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
import org.bukkit.inventory.ItemStack
import ru.arc.configs.StockConfig
import ru.arc.stock.Stock
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.guiSkull
import ru.arc.util.modify

class SymbolSelector(
    private val stockPlayer: StockPlayer,
) : ChestGui(
        4,
        TextHolder.deserialize(TextUtil.toLegacy(StockConfig.string("symbol-selector.menu-title"))),
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
        val guiItemList =
            StockMarket
                .stocks()
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

        back =
            guiItem(Material.BLUE_STAINED_GLASS_PANE) {
                onClick { click ->
                    click.isCancelled = true
                    (click.whoClicked as Player).performCommand(StockConfig.mainMenuBackCommand)
                }
                tagResolver(tagResolver)
                display("<gray>Назад")
                lore(emptyList())
                modelData(11013)
                fromConfig(StockConfig.config(), "locale.symbol-selector.back")
            }
        pane.addItem(back, 0, 0)

        all =
            guiItem(Material.GREEN_STAINED_GLASS_PANE) {
                onClick { click ->
                    click.isCancelled = true
                    GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, null) }, click.whoClicked)
                }
                tagResolver(tagResolver)
                display("           <gold>Все позиции")
                lore(
                    listOf(
                        "             <gray><strikethrough>             ",
                        "",
                        "      <white>💼 Всего позиций: <#4CAF50><position_count>",
                        "",
                        "   <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы открыть <#8c8c8c>•  ",
                        "",
                    ),
                )
                fromConfig(StockConfig.config(), "locale.symbol-selector.all-positions")
            }
        pane.addItem(all, 4, 0)

        profile =
            guiSkull(stockPlayer.playerUuid) {
                onClick { click ->
                    click.isCancelled = true
                    GuiUtils.constructAndShowAsync({ ProfileMenu(stockPlayer, 0, null) }, click.whoClicked)
                }
                tagResolver(tagResolver)
                display("              <gold>Профиль")
                lore(
                    listOf(
                        "             <gray><strikethrough>             ",
                        "",
                        "      <white>₪ Ваш баланс: <#2196F3><balance><white>💰  ",
                        "      <white>💼 Всего позиций: <#4CAF50><position_count>",
                        "",
                        "   <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы пополнить <#8c8c8c>•  ",
                        "",
                    ),
                )
                fromConfig(StockConfig.config(), "locale.symbol-selector.profile")
            }
        pane.addItem(profile, 8, 0)

        val topNavigation = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 0), topNavigation)
        market =
            guiItem(Material.BELL) {
                onClick { click ->
                    click.isCancelled = true
                    val p = click.whoClicked as Player
                    p.performCommand(StockConfig.string("market-command"))
                    p.closeInventory()
                }
                display("                 <gold>Биржа")
                lore(
                    listOf(
                        "                 <gray><strikethrough>        ",
                        " <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы отправиться <#8c8c8c>•  ",
                    ),
                )
                fromConfig(StockConfig.config(), "locale.symbol-selector.market")
            }
        topNavigation.addItem(market, 4, 0)
    }

    private fun stockItem(stock: Stock): GuiItem {
        val positions = stockPlayer.positions(stock.symbol)
        val size = positions?.size ?: 0
        val baseStack = stock.icon?.stack() ?: ItemStack(Material.BARRIER)
        return baseStack
            .modify {
                display(stock.display)
                lore(stock.lore)
                tagResolver(stock.tagResolver())
                tag("positions_in_symbol", size.toString())
            }.let { stack ->
                guiItem(stack) {
                    onClick { click ->
                        click.isCancelled = true
                        GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, stock.symbol) }, click.whoClicked)
                    }
                }
            }
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
