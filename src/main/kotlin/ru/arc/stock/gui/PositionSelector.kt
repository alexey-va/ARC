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
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.configs.StockConfig
import ru.arc.stock.Position
import ru.arc.stock.StockPlayer
import ru.arc.util.GuiUtils
import ru.arc.util.GuiUtils.cooldownCheck
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.util.TextUtil.strip
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.guiSkull
import ru.arc.util.itemStack
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
                "symbol",
                symbol ?: "null",
            ),
        ),
    ) {
    private var positions: List<Position>? = symbol?.let { stockPlayer.positions(it) } ?: stockPlayer.positions()
    private val rows: Int
    private lateinit var back: GuiItem
    private lateinit var create: GuiItem
    private lateinit var profile: GuiItem
    private var refreshTask: BukkitTask? = null
    private lateinit var paginatedPane: PaginatedPane
    private val positionItemsByUuid = linkedMapOf<java.util.UUID, GuiItem>()
    private var lastRefreshFingerprint: String? = null

    init {
        rows =
            if (positions == null) {
                2
            } else {
                max(2, min(6, (ceil(positions!!.size / 9.0)).toInt() + 1))
            }
        setRows(rows)

        setupBackground()
        setupPositions()
        setupNav()

        refreshTask =
            ARC.instance.server.scheduler.runTaskTimerAsynchronously(
                ARC.instance,
                Runnable {
                    if (viewers.isEmpty()) {
                        refreshTask?.cancel()
                        return@Runnable
                    }
                    Bukkit.getScheduler().runTask(ARC.instance, Runnable { refreshPositionsInPlace() })
                },
                20L,
                100L,
            )
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
        positionItemsByUuid.clear()
        val guiItemList = positions?.map { positionItem(it) } ?: emptyList()
        paginatedPane.populateWithGuiItems(guiItemList)
        lastRefreshFingerprint = positions?.let { fingerprint(it) }
    }

    /**
     * Updates live position rows without clearing the pane (keeps page + click handlers).
     * Full rebuild only when the position list structure changes.
     */
    private fun refreshPositionsInPlace() {
        val newPositions = symbol?.let { stockPlayer.positions(it) } ?: stockPlayer.positions()
        val newIds = positionIds(newPositions)
        val oldIds = positionIds(positions)

        if (newIds != oldIds) {
            positions = newPositions
            populatePositions()
            update()
            return
        }

        val fingerprint = fingerprint(newPositions)
        if (fingerprint == lastRefreshFingerprint) return

        lastRefreshFingerprint = fingerprint
        positions = newPositions
        for (position in newPositions) {
            positionItemsByUuid[position.positionUuid]?.setItem(buildPositionStack(position))
        }
        update()
    }

    private fun positionIds(list: List<Position>?): List<java.util.UUID> = list?.map { it.positionUuid } ?: emptyList()

    private fun fingerprint(list: List<Position>): String {
        val balance = stockPlayer.getBalance()
        val autoTake = stockPlayer.autoTake
        return list.joinToString("|") { position ->
            val autoClose = position.marginCallAtPrice(balance, autoTake)
            buildString {
                append(position.positionUuid)
                append(':')
                append(position.gains())
                append(':')
                append(position.getStock()?.price ?: 0.0)
                append(':')
                append(autoClose.low)
                append(':')
                append(autoClose.high)
            }
        }
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, rows - 1), pane)
        val tagResolver = customResolver()

        back =
            guiItem(Material.BLUE_STAINED_GLASS_PANE) {
                onClick { click ->
                    click.isCancelled = true
                    GuiUtils.constructAndShowAsync({ SymbolSelector(stockPlayer) }, click.whoClicked)
                }
                display("<gray>Назад")
                lore(emptyList())
                tagResolver(tagResolver)
                modelData(11013)
                fromConfig(StockConfig.config(), "locale.position-selector.back")
            }
        pane.addItem(back, 0, 0)

        val canHaveMore = stockPlayer.isBelowMaxStockAmount() && !(positions != null && positions!!.size >= 9)
        if (symbol != null) {
            create =
                guiItem(
                    if (canHaveMore) Material.GREEN_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE,
                ) {
                    onClick { click ->
                        click.isCancelled = true
                        val more = stockPlayer.isBelowMaxStockAmount() && !(positions != null && positions!!.size >= 9)
                        if (!more) return@onClick
                        if (!cooldownCheck(back, click.whoClicked.uniqueId, this@PositionSelector)) return@onClick
                        val player = click.whoClicked as Player
                        if (player.hasPermission("arc.stocks.buy")) {
                            GuiUtils.constructAndShowAsync({ PositionCreator(stockPlayer, symbol) }, click.whoClicked)
                        } else {
                            player.sendMessage(TextUtil.noPermissions())
                        }
                    }
                    if (canHaveMore) {
                        display("              <gold>Купить")
                        lore(
                            listOf(
                                "             <gray><strikethrough>             ",
                                "",
                                "      <white>💼 У вас позиций: <position_count>",
                                "      <white>🛑 Ваш лимит: <max_stock_amount>",
                                "",
                                "   <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы открыть <#8c8c8c>•  ",
                                "",
                            ),
                        )
                        tagResolver(tagResolver)
                        tag("max_stock_amount", stockPlayer.maxStockAmount().toString())
                        fromConfig(StockConfig.config(), "locale.position-selector.create")
                    } else {
                        display(" <red>Вы достигли лимита активов ")
                        lore(
                            listOf(
                                "            <gray><strikethrough>               ",
                                "",
                                "    <white>💼 У вас позиций: <#4CAF50><position_amount>",
                                "    <white>🛑 Ваш лимит: <#E91E63><max_stock_amount>",
                                "    <white>🛑 Максимум в <yellow><symbol><white>: <#E91E63>9",
                                "",
                            ),
                        )
                        tagResolver(tagResolver)
                        tag("max_stock_amount", stockPlayer.maxStockAmount().toString())
                        fromConfig(StockConfig.config(), "locale.position-selector.create-limit")
                    }
                }
            pane.addItem(create, 4, 0)
        }

        profile =
            guiSkull(stockPlayer.playerUuid) {
                onClick { click ->
                    click.isCancelled = true
                    GuiUtils.constructAndShowAsync({ ProfileMenu(stockPlayer, 1, symbol) }, click.whoClicked)
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
                fromConfig(StockConfig.config(), "locale.position-selector.profile")
            }
        pane.addItem(profile, 8, 0)
    }

    private fun customResolver(): TagResolver =
        TagResolver
            .builder()
            .resolver(TagResolver.resolver("balance", Tag.inserting(mm(TextUtil.formatAmount(stockPlayer.getBalance()), true))))
            .resolver(TagResolver.resolver("total_balance", Tag.inserting(mm(TextUtil.formatAmount(stockPlayer.totalBalance()), true))))
            .resolver(TagResolver.resolver("positions_count", Tag.inserting(mm(stockPlayer.positions().size.toString(), true))))
            .resolver(
                TagResolver.resolver(
                    "symbol",
                    Tag.inserting(strip(MiniMessage.miniMessage().deserialize(symbol ?: "символ")) ?: Component.text(symbol ?: "символ")),
                ),
            ).resolver(stockPlayer.tagResolver())
            .build()

    private fun positionItem(position: Position): GuiItem {
        val item =
            guiItem(buildPositionStack(position)) {
                onClick { click ->
                    click.isCancelled = true
                    GuiUtils.constructAndShowAsync(
                        { PositionMenu(stockPlayer, position, symbol == null) },
                        click.whoClicked,
                    )
                }
            }
        positionItemsByUuid[position.positionUuid] = item
        return item
    }

    private fun buildPositionStack(position: Position): ItemStack {
        val autoClosePrices = position.marginCallAtPrice(stockPlayer.getBalance(), stockPlayer.autoTake)
        return itemStack(position.iconMaterial) {
            display("                    <dark_gray>💼 <gold><uuid> <dark_gray>💼")
            lore(
                listOf(
                    "                     <gray><strikethrough>               ",
                    "",
                    "  <white>🔍 Символ: <gold><symbol>  ",
                    "  <white>۞ Текущая прибыль: <#4CAF50><position_gains><white>💰  ",
                    "  <white>🔥 Комиссия при покупке: <#4CAF50><commission><white>💰  ",
                    "  <white>📈 Общая прибыль без дивидендов: <#4CAF50><total_position_gains><white>💰  ",
                    "  <white>💸 Получено дивидендов: <#FF9800><received_dividend><white>💰  ",
                    "  <white>🔄 Дивиденды за цикл: <#FF9800><dividend_amount><white>💰  ",
                    "  <white>₪ Общая прибыль с дивидендами: <#FF9800><total_gains_with_dividends><white>💰  ",
                    "",
                    "  <white>📄 Тип: <#E91E63><type>  ",
                    "  <white>🔢 Количество: <#E91E63><amount>  ",
                    "  <white>📈 Верхняя граница маржи: <#E91E63><upper><white>💰  ",
                    "  <white>📉 Нижняя граница маржи: <#E91E63><lower><white>💰  ",
                    "  <white>🛑 Авто закроется при падении до: <#E91E63><close_at_low><white>💰  ",
                    "  <white>🛑 Авто закроется при росте до: <#E91E63><close_at_high><white>💰  ",
                    "",
                    "  <white>🏁 Начальная цена: <#E91E63><starting_price><white>💰  ",
                    "  <white>🛒 Цена покупки: <#E91E63><buy_price><white>💰  ",
                    "  <white>🔍 Текущая цена актива: <#E91E63><stock_price><white>💰  ",
                    "  <white>⚖ Рычаг: <#E91E63><leverage>  ",
                    "  <white>💹 Стоимость с учетом рычага: <#E91E63><leveraged_price><white>💰  ",
                    "  <white> ",
                    "  <white>⌛ Куплено часов назад: <#E91E63><hours_since_bought>  ",
                    "",
                    "           <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы закрыть <#8c8c8c>•  ",
                    "",
                ),
            )
            tagResolver(position.resolver())
            tag("close_at_low", if (autoClosePrices.low == -1.0) "<red>Нет" else formatAmount(autoClosePrices.low))
            tag("close_at_high", if (autoClosePrices.high == -1.0) "<red>Нет" else formatAmount(autoClosePrices.high))
            fromConfig(StockConfig.config(), "locale.position-selector.position")
        }
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
