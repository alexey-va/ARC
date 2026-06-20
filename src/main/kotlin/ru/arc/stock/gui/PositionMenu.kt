package ru.arc.stock.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.configs.StockConfig
import ru.arc.util.fromConfig
import ru.arc.stock.Position
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.util.guiItem
import ru.arc.util.itemComponents
import ru.arc.util.itemLore

class PositionMenu(
    private val stockPlayer: StockPlayer,
    private val position: Position,
    private val fromAllPositions: Boolean,
) : ChestGui(
    2,
    TextHolder.deserialize(
        TextUtil.toLegacy(
            StockConfig.string("position-menu.menu-title"),
            "uuid", position.positionUuid.toString().split("-")[0],
            "symbol", position.symbol,
        )
    )
) {
    private lateinit var back: GuiItem
    private lateinit var close: GuiItem
    private var confirm = false

    init {
        setupBackground()
        setupButtons()
        setupNav()
    }

    private fun setupButtons() {
        val resolver = position.resolver()
        val staticPane = StaticPane(9, 1)
        staticPane.addItem(infoItem(), 1, 0)
        staticPane.addItem(closeItem(resolver), 7, 0)
        this.addPane(Slot.fromXY(0, 0), staticPane)
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 1), pane)

        back = guiItem(Material.BLUE_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({
                    if (fromAllPositions) PositionSelector(stockPlayer, null)
                    else PositionSelector(stockPlayer, position.symbol)
                }, click.whoClicked, 8)
            }
            display("<gray>Назад")
            lore(emptyList())
            modelData(11013)
            fromConfig(StockConfig.config(), "locale.position-menu.back")
        }
        pane.addItem(back, 0, 0)
    }

    private fun closeItem(resolver: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver): GuiItem {
        close = guiItem(Material.RED_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                if (!confirm) {
                    val stockConfig = StockConfig.config()
                    val (display, lore) =
                        stockConfig.itemComponents("locale.position-menu.close-button-confirm", resolver)
                    GuiUtils.temporaryChange(
                        close.item,
                        display,
                        lore.ifEmpty {
                            stockConfig.itemLore("locale.position-menu.close-button").map { mm(it, resolver) }
                        },
                        100L
                    ) {
                        this@PositionMenu.confirm = false
                        this@PositionMenu.update()
                    }
                    confirm = true
                    update()
                    return@onClick
                }
                (click.whoClicked as Player).performCommand(
                    "arc-invest -t:close -s:${position.symbol} -uuid:${position.positionUuid} -reason:3"
                )
                PositionSelector(stockPlayer, position.symbol).show(click.whoClicked)
            }
            display("<red>Закрыть позицию")
            lore(listOf(
                "<gray>Нажмите чтобы закрыть с прибылью: <total_position_gains><white>💰",
                "<gray>Деньги будут отправлены на ваш брокерский счет",
            ))
            tagResolver(resolver)
            fromConfig(StockConfig.config(), "locale.position-menu.close-button")
        }
        return close
    }

    private fun infoItem(): GuiItem {
        val resolver = position.resolver()
        val autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.autoTake)
        return guiItem(Material.BLUE_STAINED_GLASS_PANE) {
            onClick { click -> click.isCancelled = true }
            display("         <gold>Информация")
            lore(listOf(
                "                     <gray><strikethrough>               ",
                "",
                "  <white>🔍 Символ: <gold><symbol>",
                "  <white>۞ Текущая прибыль: <#4CAF50><position_gains><white>💰",
                "  <white>🔥 Комиссия при покупке: <#4CAF50><commission><white>💰",
                "  <white>📈 Общая прибыль без дивидендов: <#4CAF50><total_position_gains><white>💰",
                "  <white>💸 Получено дивидендов: <#FF9800><received_dividend><white>💰",
                "  <white>🔄 Дивиденды за цикл: <#FF9800><dividend_amount><white>💰",
                "  <white>₪ Общая прибыль с дивидендами: <#FF9800><total_gains_with_dividends><white>💰",
                "",
                "  <white>📄 Тип: <#E91E63><type>",
                "  <white>🔢 Количество: <#E91E63><amount>",
                "  <white>📈 Верхняя граница маржи: <#E91E63><upper><white>💰",
                "  <white>📉 Нижняя граница маржи: <#E91E63><lower><white>💰",
                "  <white>🛑 Авто закроется при падении до: <#E91E63><close_at_low><white>💰",
                "  <white>🛑 Авто закроется при росте до: <#E91E63><close_at_high><white>💰",
                "",
                "  <white>🏁 Начальная цена: <#E91E63><starting_price><white>💰",
                "  <white>🛒 Цена покупки: <#E91E63><buy_price><white>💰",
                "  <white>🔍 Текущая цена актива: <#E91E63><stock_price><white>💰",
                "  <white>⚖ Рычаг: <#E91E63><leverage>",
                "  <white>💹 Стоимость с учетом рычага: <#E91E63><leveraged_price><white>💰",
                "  <white> ",
                "  <white>⌛ Куплено часов назад: <#E91E63><hours_since_bought>",
                "",
            ))
            tagResolver(resolver)
            tag("close_at_low", if (autoClosePrices.low == -1.0) "<red>Нет" else formatAmount(autoClosePrices.low))
            tag("close_at_high", if (autoClosePrices.high == -1.0) "<red>Нет" else formatAmount(autoClosePrices.high))
            fromConfig(StockConfig.config(), "locale.position-menu.info-button")
        }
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)
    }
}
