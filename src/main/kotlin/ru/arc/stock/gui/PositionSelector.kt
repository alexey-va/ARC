package ru.arc.stock.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.configs.StockConfig
import ru.arc.stock.Position
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.GuiUtils.cooldownCheck
import ru.arc.util.ItemStackBuilder
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.util.TextUtil.strip
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class PositionSelector(
    private val stockPlayer: StockPlayer,
    private val symbol: String?,
) : ChestGui(
    2,
    TextHolder.deserialize(
        TextUtil.toLegacy(
            StockConfig.string(if (symbol == null) "position-selector.all-positions-menu-title" else "position-selector.menu-title"),
            "symbol", symbol ?: "null",
        )
    )
) {
    private var positions: List<Position>? = symbol?.let { stockPlayer.positions(it) } ?: stockPlayer.positions()
    private val rows: Int
    private lateinit var back: GuiItem
    private lateinit var create: GuiItem
    private lateinit var profile: GuiItem
    private var refreshTask: BukkitTask? = null
    private lateinit var paginatedPane: PaginatedPane

    init {
        rows = if (positions == null) 2
        else max(2, min(6, (ceil(positions!!.size / 9.0)).toInt() + 1))
        setRows(rows)

        setupBackground()
        setupPositions()
        setupNav()

        refreshTask = ARC.instance.server.scheduler.runTaskTimerAsynchronously(ARC.instance, Runnable {
            if (viewers.isEmpty()) {
                refreshTask?.cancel()
                return@Runnable
            }
            positions = symbol?.let { stockPlayer.positions(it) } ?: stockPlayer.positions()
            populatePositions()
            Bukkit.getScheduler().runTask(ARC.instance, Runnable { update() })
        }, 20L, 100L)
        this.setOnClose { cancelTasks() }
    }

    fun cancelTasks() {
        refreshTask?.takeUnless { it.isCancelled }?.cancel()
    }

    private fun setupPositions() {
        paginatedPane = PaginatedPane(9, rows - 1)
        this.addPane(Slot.fromXY(0, 0), paginatedPane)
        populatePositions()
    }

    private fun populatePositions() {
        paginatedPane.clear()
        val guiItemList = positions?.map { positionItem(it) } ?: emptyList()
        paginatedPane.populateWithGuiItems(guiItemList)
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, rows - 1), pane)
        val tagResolver = customResolver()

        back = ItemStackBuilder(Material.BLUE_STAINED_GLASS_PANE)
            .display(StockConfig.string("position-selector.back-display"))
            .lore(StockConfig.stringList("position-selector.back-lore"))
            .tagResolver(tagResolver)
            .modelData(11013)
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ SymbolSelector(stockPlayer) }, click.whoClicked)
            }.build()
        pane.addItem(back, 0, 0)

        val canHaveMore = stockPlayer.isBelowMaxStockAmount() && !(positions != null && positions!!.size >= 9)
        if (symbol != null) {
            create = ItemStackBuilder(if (canHaveMore) Material.GREEN_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE)
                .display(if (canHaveMore) StockConfig.string("position-selector.create-display") else StockConfig.string("position-selector.create-display-limit"))
                .lore(if (canHaveMore) StockConfig.stringList("position-selector.create-lore") else StockConfig.stringList("position-selector.create-lore-limit"))
                .tagResolver(tagResolver)
                .appendResolver("max_stock_amount", stockPlayer.maxStockAmount().toString())
                .toGuiItemBuilder()
                .clickEvent { click ->
                    click.isCancelled = true
                    val more = stockPlayer.isBelowMaxStockAmount() && !(positions != null && positions!!.size >= 9)
                    if (!more) return@clickEvent
                    if (!cooldownCheck(back, click.whoClicked.uniqueId, this)) return@clickEvent
                    val player = click.whoClicked as Player
                    if (player.hasPermission("arc.stocks.buy")) {
                        GuiUtils.constructAndShowAsync({ PositionCreator(stockPlayer, symbol) }, click.whoClicked)
                    } else player.sendMessage(TextUtil.noPermissions())
                }.build()
            pane.addItem(create, 4, 0)
        }

        profile = ItemStackBuilder(Material.PLAYER_HEAD)
            .skull(stockPlayer.playerUuid)
            .tagResolver(tagResolver)
            .display(StockConfig.string("position-selector.profile-display"))
            .lore(StockConfig.stringList("position-selector.profile-lore"))
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ ProfileMenu(stockPlayer, 1, symbol) }, click.whoClicked)
            }.build()
        pane.addItem(profile, 8, 0)
    }

    private fun customResolver(): TagResolver = TagResolver.builder()
        .resolver(TagResolver.resolver("balance", Tag.inserting(mm(TextUtil.formatAmount(stockPlayer.getBalance()), true))))
        .resolver(TagResolver.resolver("total_balance", Tag.inserting(mm(TextUtil.formatAmount(stockPlayer.totalBalance()), true))))
        .resolver(TagResolver.resolver("positions_count", Tag.inserting(mm(stockPlayer.positions().size.toString(), true))))
        .resolver(TagResolver.resolver("symbol", Tag.inserting(strip(MiniMessage.miniMessage().deserialize(symbol ?: "символ")) ?: Component.text(symbol ?: "символ"))))
        .resolver(stockPlayer.tagResolver())
        .build()

    private fun positionItem(position: Position): GuiItem {
        val autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.autoTake)
        return ItemStackBuilder(position.iconMaterial)
            .display(StockConfig.string("position-selector.position-display"))
            .lore(StockConfig.stringList("position-selector.position-lore"))
            .tagResolver(position.resolver())
            .appendResolver("close_at_low", if (autoClosePrices.low == -1.0) "<red>Нет" else formatAmount(autoClosePrices.low))
            .appendResolver("close_at_high", if (autoClosePrices.high == -1.0) "<red>Нет" else formatAmount(autoClosePrices.high))
            .toGuiItemBuilder()
            .clickEvent { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync(
                    { PositionMenu(stockPlayer, position, symbol == null) },
                    click.whoClicked
                )
            }.build()
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 1, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, rows - 1), pane)

        val pane2 = OutlinePane(9, rows - 1, Pane.Priority.LOWEST)
        pane2.addItem(GuiUtils.background(Material.LIGHT_GRAY_STAINED_GLASS_PANE))
        pane2.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane2)
    }
}
