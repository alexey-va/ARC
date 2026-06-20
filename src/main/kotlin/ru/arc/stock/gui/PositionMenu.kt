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
import ru.arc.stock.Position
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.ItemStackBuilder
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm

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

        back = ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
            .display(StockConfig.string("position-menu.back-display"))
            .lore(StockConfig.stringList("position-menu.back-lore"))
            .modelData(11013)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({
                    if (fromAllPositions) PositionSelector(stockPlayer, null)
                    else PositionSelector(stockPlayer, position.symbol)
                }, click.whoClicked, 8)
            }.build()
        pane.addItem(back, 0, 0)
    }

    private fun closeItem(resolver: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver): GuiItem {
        close = ItemStackBuilder(Material.RED_STAINED_GLASS_PANE)
            .display(StockConfig.string("position-menu.close-button-display"))
            .lore(StockConfig.stringList("position-menu.close-button-lore"))
            .tagResolver(resolver)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                if (!confirm) {
                    GuiUtils.temporaryChange(
                        close.item,
                        mm(StockConfig.string("position-menu.close-button-confirm-display"), resolver),
                        StockConfig.stringList("position-menu.close-button-lore").map { mm(it, resolver) },
                        100L
                    ) {
                        this.confirm = false
                        this.update()
                    }
                    confirm = true
                    update()
                    return@clickEvent
                }
                (click.whoClicked as Player).performCommand(
                    "arc-invest -t:close -s:${position.symbol} -uuid:${position.positionUuid} -reason:3"
                )
                PositionSelector(stockPlayer, position.symbol).show(click.whoClicked)
            }.build()
        return close
    }

    private fun infoItem(): GuiItem {
        val resolver = position.resolver()
        val autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.autoTake)
        return ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
            .display(StockConfig.string("position-menu.info-button-display"))
            .lore(StockConfig.stringList("position-menu.info-button-lore"))
            .tagResolver(resolver)
            .appendResolver("close_at_low", if (autoClosePrices.low == -1.0) "<red>Нет" else formatAmount(autoClosePrices.low))
            .appendResolver("close_at_high", if (autoClosePrices.high == -1.0) "<red>Нет" else formatAmount(autoClosePrices.high))
            .toGuiItemBuilder()
            .clickEvent { click -> click.isCancelled = true }.build()
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)
    }
}
