package ru.arc.stock.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import ru.arc.configs.StockConfig
import ru.arc.util.fromConfig
import ru.arc.core.modules.EconomyModule
import ru.arc.stock.StockPlayer
import ru.arc.stock.StockPlayerManager
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
import ru.arc.util.guiItem
import ru.arc.util.itemComponents
import ru.arc.util.itemStack
import kotlin.math.max

class ProfileMenu(
    private val stockPlayer: StockPlayer,
    private val previous: Int,
    private val symbol: String?,
) : ChestGui(
    2,
    TextHolder.deserialize(
        TextUtil.toLegacy(
            StockConfig.string("profile-menu.menu-title"),
            "name", stockPlayer.playerName,
        )
    )
) {
    private lateinit var back: GuiItem
    private lateinit var balance: GuiItem
    private lateinit var statistic: GuiItem
    private lateinit var auto: GuiItem

    init {
        setupBackground()
        setupNav()
        setupButtons()
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 1), pane)

        back = guiItem(Material.BLUE_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                if (previous == 0) {
                    GuiUtils.constructAndShowAsync({ SymbolSelector(stockPlayer) }, click.whoClicked)
                } else if (previous == 1) {
                    GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, symbol) }, click.whoClicked)
                }
            }
            fromConfig(StockConfig.config(), "locale.profile-menu.back")
        }
        pane.addItem(back, 0, 0)
    }

    private fun setupButtons() {
        val staticPane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 0), staticPane)
        val tagResolver = stockPlayer.tagResolver()

        statistic = guiItem(Material.PAPER) {
            onClick { click -> click.isCancelled = true }
            display("<yellow>Статистика")
            lore(listOf(
                "<gray>Текущая прибыль: <gains><white>💰",
                "<gray>Общая прибыль: <total_gains><white>💰",
                "<gray>Получено дивидендов: <received_dividends><white>💰",
                "<gray>Количество позиций: <position_count>",
            ))
            tagResolver(tagResolver)
            fromConfig(StockConfig.config(), "locale.profile-menu.statistic")
        }
        staticPane.addItem(statistic, 1, 0)

        balance = guiItem(Material.STICK) {
            onClick(::acceptBalanceClick)
            display("<gold>Баланс")
            lore(listOf(
                "<gray>Баланс: <balance><white>💰",
                "<gray>Суммарный баланс: <total_balance><white>💰",
            ))
            tagResolver(tagResolver)
            fromConfig(StockConfig.config(), "locale.profile-menu.balance")
        }
        staticPane.addItem(balance, 3, 0)

        auto = guiItem(Material.LEVER) {
            onClick { click ->
                click.isCancelled = true
                stockPlayer.updateAutoTake(!stockPlayer.autoTake)
                auto.setItem(buildAutoTakeItem())
                update()
            }
            display("<green>Автоматически пополнять счет")
            lore(listOf("<gray>Включено: <auto_take>"))
            tagResolver(tagResolver)
            fromConfig(StockConfig.config(), "locale.profile-menu.auto-take")
        }
        staticPane.addItem(auto, 5, 0)
    }

    private fun buildAutoTakeItem() =
        itemStack(Material.LEVER) {
            display("<green>Автоматически пополнять счет")
            lore(listOf("<gray>Включено: <auto_take>"))
            tagResolver(stockPlayer.tagResolver())
            fromConfig(StockConfig.config(), "locale.profile-menu.auto-take")
        }

    private fun buildBalanceItem() =
        itemStack(Material.STICK) {
            display("<gold>Баланс")
            lore(listOf(
                "<gray>Баланс: <balance><white>💰",
                "<gray>Суммарный баланс: <total_balance><white>💰",
            ))
            tagResolver(stockPlayer.tagResolver())
            fromConfig(StockConfig.config(), "locale.profile-menu.balance")
        }

    private fun getNewBalance(click: InventoryClickEvent): Double {
        var newBalance = stockPlayer.getBalance()
        if (click.isLeftClick) {
            newBalance += if (click.isShiftClick) 10000.0 else 1000.0
        } else if (click.isRightClick) {
            newBalance = max(1.0, newBalance - if (click.isShiftClick) 10000.0 else 1000.0)
        }
        return newBalance
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)
    }

    private fun acceptBalanceClick(click: InventoryClickEvent) {
        val newBalance = getNewBalance(click)
        val diff = newBalance - stockPlayer.getBalance()
        val totalGains = stockPlayer.totalGainsList()

        if (totalGains < 0 && Math.abs(totalGains) > newBalance) {
            val stockConfig = StockConfig.config()
            val (display, lore) = stockConfig.itemComponents("locale.profile-menu.will-go-bankrupt")
            GuiUtils.temporaryChange(balance.item, display, lore, 100L, ::update)
            this.update()
            return
        }

        val offlinePlayer = Bukkit.getOfflinePlayer(stockPlayer.playerUuid)
        val econ = EconomyModule.getEconomy()
        val playerBalance = econ?.getBalance(offlinePlayer) ?: 0.0
        if (diff > 0 && diff > playerBalance) {
            val resolver = TagResolver.resolver("player_balance", Tag.inserting(
                mm(TextUtil.formatAmount(playerBalance), true)
            ))
            val stockConfig = StockConfig.config()
            val (display, lore) = stockConfig.itemComponents("locale.profile-menu.not-enough-money", resolver)
            GuiUtils.temporaryChange(balance.item, display, lore, 100L, ::update)
            this.update()
            return
        }

        StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, diff)
        balance.setItem(buildBalanceItem())
        this.update()
    }
}
