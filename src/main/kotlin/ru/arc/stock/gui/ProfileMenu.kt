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
import ru.arc.core.modules.EconomyModule
import ru.arc.stock.StockPlayer
import ru.arc.stock.StockPlayerManager
import ru.arc.util.GuiUtils
import ru.arc.util.ItemStackBuilder
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.mm
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

        back = ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
            .display(StockConfig.string("profile-menu.back-display"))
            .lore(StockConfig.stringList("profile-menu.back-lore"))
            .modelData(11013)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                if (previous == 0) {
                    GuiUtils.constructAndShowAsync({ SymbolSelector(stockPlayer) }, click.whoClicked)
                } else if (previous == 1) {
                    GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, symbol) }, click.whoClicked)
                }
            }.build()
        pane.addItem(back, 0, 0)
    }

    private fun setupButtons() {
        val staticPane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 0), staticPane)
        val tagResolver = stockPlayer.tagResolver()

        statistic = ItemStackBuilder(Material.PAPER)
            .display(StockConfig.string("profile-menu.statistic-display"))
            .lore(StockConfig.stringList("profile-menu.statistic-lore"))
            .tagResolver(tagResolver)
            .toGuiItemBuilder()
            .clickEvent { click -> click.isCancelled = true }.build()
        staticPane.addItem(statistic, 1, 0)

        balance = ItemStackBuilder(Material.STICK)
            .modelData(11138)
            .display(StockConfig.string("profile-menu.balance-display"))
            .lore(StockConfig.stringList("profile-menu.balance-lore"))
            .tagResolver(tagResolver)
            .toGuiItemBuilder()
            .clickEvent(::acceptBalanceClick).build()
        staticPane.addItem(balance, 3, 0)

        auto = ItemStackBuilder(Material.LEVER)
            .display(StockConfig.string("profile-menu.auto-take-display"))
            .lore(StockConfig.stringList("profile-menu.auto-take-lore"))
            .tagResolver(tagResolver)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                stockPlayer.updateAutoTake(!stockPlayer.autoTake)
                auto.setItem(
                    ItemStackBuilder(Material.LEVER)
                        .display(StockConfig.string("profile-menu.auto-take-display"))
                        .lore(StockConfig.stringList("profile-menu.auto-take-lore"))
                        .tagResolver(stockPlayer.tagResolver()).build()
                )
                update()
            }.build()
        staticPane.addItem(auto, 5, 0)
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
            GuiUtils.temporaryChange(
                balance.item,
                mm(StockConfig.string("profile-menu.will-go-bankrupt-display")),
                StockConfig.stringList("profile-menu.will-go-bankrupt-lore").map { TextUtil.mm(it) },
                100L, ::update
            )
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
            GuiUtils.temporaryChange(
                balance.item,
                mm(StockConfig.string("profile-menu.not-enough-money-display"), resolver),
                StockConfig.stringList("profile-menu.not-enough-money-lore").map { mm(it, resolver) },
                100L, ::update
            )
            this.update()
            return
        }

        StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, diff)
        balance.setItem(
            ItemStackBuilder(Material.STICK)
                .modelData(11138)
                .display(StockConfig.string("profile-menu.balance-display"))
                .lore(StockConfig.stringList("profile-menu.balance-lore"))
                .tagResolver(stockPlayer.tagResolver()).build()
        )
        this.update()
    }
}
