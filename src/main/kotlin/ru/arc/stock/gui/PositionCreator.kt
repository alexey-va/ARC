package ru.arc.stock.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import ru.arc.ARC
import ru.arc.config.StockConfig
import ru.arc.util.fromConfig
import ru.arc.core.ScheduledTask
import ru.arc.stock.Position
import ru.arc.stock.Stock
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayer
import ru.arc.stock.StockPlayerManager
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.util.TextUtil.strip
import ru.arc.util.guiItem
import ru.arc.util.itemStack
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.min

class PositionCreator(
    private val stockPlayer: StockPlayer,
    private val symbol: String,
) : ChestGui(
    2,
    TextHolder.deserialize(
        TextUtil.toLegacy(StockConfig.string("position-creator.menu-title"), "symbol", symbol)
    )
) {
    private val stock: Stock = StockMarket.stock(symbol) ?: error("Stock not found: $symbol")
    private lateinit var back: GuiItem
    private lateinit var amountItem: GuiItem
    private lateinit var typeItem: GuiItem
    private lateinit var leverageItem: GuiItem
    private lateinit var createItem: GuiItem
    private lateinit var upperItem: GuiItem
    private lateinit var lowerItem: GuiItem
    private var amountTask: ScheduledTask? = null

    private var amount = 1.0
    private var leverage: Int
    private var upper = Double.MAX_VALUE
    private var lower = Double.MAX_VALUE
    private var type = Position.Type.BOUGHT

    init {
        leverage = when {
            stock.price < 1 -> 10000
            stock.price < 10 -> 1000
            stock.price < 100 -> 100
            stock.price < 1000 -> 10
            else -> 1
        }
        if (leverage > stock.maxLeverage) leverage = stock.maxLeverage
        while (leverage * amount * stock.price > StockConfig.maxLeveragedPrice) {
            if (leverage == 1) break
            leverage /= 10
            if (leverage < 1) leverage = 1
        }

        setupBackground()
        setupNav()
        setupButtons()
    }

    private fun setupButtons() {
        val staticPane = StaticPane(9, 1)
        val resolver = resolver(amount, type, leverage)

        amountItem = guiItem(Material.GOLD_INGOT) {
            onClick(::acceptAmountClick)
            tagResolver(resolver)
            display("                      <gold>Количество")
            lore(listOf(
                "                       <gray><strikethrough>             ",
                "",
                "  <white>💼 Количество: <#2196F3><amount>  ",
                "",
                "  <white>💲 Цена:<#4CAF50> <cost><white>💰  ",
                "  <white>🔥 Комиссия: <#4CAF50><commission><white>💰  ",
                "  <white>📈 Общая цена: <#4CAF50><total_cost><white>💰  ",
                "  <white>₪ Ваш баланс: <#2196F3><balance><white>💰  ",
                "",
                "  <white>📈 Верхняя граница маржи: <#E91E63><upper><white>💰  ",
                "  <white>📉 Нижняя граница маржи: <#E91E63><lower><white>💰  ",
                "  <white>🛑 Авто-закроется при падении цены до: <#E91E63><close_at_low><white>💰  ",
                "  <white>🛑 Авто-закроется при росте цены до: <#E91E63><close_at_high><white>💰  ",
                "",
                "           <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                "",
            ))
            fromConfig(StockConfig.config(), "locale.position-creator.amount")
        }
        staticPane.addItem(amountItem, 0, 0)

        typeItem = guiItem(
            if (type == Position.Type.BOUGHT) Material.LAPIS_LAZULI else Material.COAL,
        ) {
            onClick(::acceptTypeClick)
            tagResolver(resolver)
            display("                 <gold>Тип")
            lore(listOf(
                "               <gray><strikethrough>         ",
                "",
                "            <white>🔥 <gray><type>",
                "",
                "   <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                "",
            ))
            fromConfig(StockConfig.config(), "locale.position-creator.type")
        }
        staticPane.addItem(typeItem, 2, 0)

        leverageItem = guiItem(Material.LEVER) {
            onClick(::acceptLeverageClick)
            display("                      <gold>Рычаг")
            lore(listOf(
                "                    <gray><strikethrough>             ",
                "",
                "  <white>⚖ Рычаг: <#E91E63><leverage>  ",
                "  <white>🛑 Максимальный рычаг: <#E91E63><max_leverage>  ",
                "  <white>💹 Стоимость с учетом рычага: <#E91E63><leveraged_price><white>💰  ",
                "  <white>🛑 Макс. стоимость с учетом рычага: <#E91E63><max_leveraged_price><white>💰  ",
                "",
                "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                "",
            ))
            tagResolver(resolver)
            fromConfig(StockConfig.config(), "locale.position-creator.leverage")
        }
        staticPane.addItem(leverageItem, 4, 0)

        upperItem = guiItem(Material.SLIME_BLOCK) {
            onClick(::acceptUpperClick)
            display("              <gold>Максимальная прибыль")
            lore(listOf(
                "                      <gray><strikethrough>             ",
                "",
                "    <white>📈 Граница: <green><upper><white>💰  ",
                "",
                "    <gray>Позиция автоматически закроется при  ",
                "    <gray>достижении <green>прибыли<gray> в указаную величину  ",
                "",
                "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                "",
            ))
            tagResolver(resolver)
            fromConfig(StockConfig.config(), "locale.position-creator.upper")
        }
        staticPane.addItem(upperItem, 6, 0)

        lowerItem = guiItem(Material.HONEY_BLOCK) {
            onClick(::acceptLowerClick)
            display("              <gold>Максимальные потери")
            lore(listOf(
                "                      <gray><strikethrough>             ",
                "",
                "    <white>📈 Граница: <green><lower><white>💰  ",
                "",
                "    <gray>Позиция автоматически закроется при  ",
                "    <gray>достижении <green>прибыли<gray> в указаную величину  ",
                "",
                "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                "",
            ))
            tagResolver(resolver)
            fromConfig(StockConfig.config(), "locale.position-creator.lower")
        }
        staticPane.addItem(lowerItem, 7, 0)

        val canHaveMore = stockPlayer.isBelowMaxStockAmount()
        createItem = guiItem(Material.GREEN_STAINED_GLASS_PANE) {
            onClick(if (!canHaveMore) { c -> c.isCancelled = true } else ::acceptCreateClick)
            if (canHaveMore) {
                display("                       <gold>Создать")
                lore(listOf(
                    "                       <gray><strikethrough>           ",
                    "",
                    "  <white>🏁 Цена: <#4CAF50><cost><white>💰  ",
                    "  <white>🔥 Комиссия: <#E91E63><commission><white>💰  ",
                    "  <white>📈 Общая цена: <#FF9800><total_cost><white>💰  ",
                    "  <white>₪ Ваш баланс: <#2196F3><balance><white>💰  ",
                    "  <white>🛑 Авто закроется при падении до: <#F44336><close_at_low><white>💰  ",
                    "  <white>🛑 Авто закроется при росте до: <#9C27B0><close_at_high><white>💰  ",
                    "  ",
                    "  <white>🛒 У вас позиций: <#FFC107><position_amount>  ",
                    "  <white>🛑 Ваш лимит: <#E91E63><max_stock_amount>  ",
                    "",
                    "           <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                tagResolver(resolver)
                fromConfig(StockConfig.config(), "locale.position-creator.create")
            } else {
                display(" <red>Вы достигли лимита активов ")
                lore(emptyList())
                tagResolver(resolver)
                fromConfig(StockConfig.config(), "locale.position-creator.create-limit")
            }
        }
        staticPane.addItem(createItem, 8, 0)

        this.addPane(Slot.fromXY(0, 0), staticPane)
    }

    private fun getNewUpper(click: InventoryClickEvent): Double {
        if (upper == Double.MAX_VALUE) return 1000.0
        return if (click.isLeftClick) {
            if (click.isShiftClick) min(upper + 1000, 100000.0) else min(upper + 100, 10000.0)
        } else if (click.isRightClick) {
            if (click.isShiftClick) max(1.0, upper - 1000) else max(1.0, upper - 100)
        } else upper
    }

    private fun getNewLower(click: InventoryClickEvent): Double {
        if (lower == Double.MAX_VALUE) return 1000.0
        return if (click.isLeftClick) {
            if (click.isShiftClick) min(lower + 1000, 1_000_000.0) else min(lower + 100, 1_000_000.0)
        } else if (click.isRightClick) {
            if (click.isShiftClick) max(1.0, lower - 1000) else max(1.0, lower - 100)
        } else lower
    }

    private fun getNewLeverage(click: InventoryClickEvent): Int {
        return if (click.isLeftClick) {
            if (click.isShiftClick) {
                if (leverage == 1) 100 else min(leverage + 100, stock.maxLeverage)
            } else {
                if (leverage == 1) 10 else min(leverage + 10, stock.maxLeverage)
            }
        } else if (click.isRightClick) {
            if (click.isShiftClick) max(1, leverage - 100) else max(1, leverage - 10)
        } else leverage
    }

    private fun resolver(amount: Double, type: Position.Type, leverage: Int): TagResolver {
        val decimalFormat = DecimalFormat()
        decimalFormat.applyPattern(if (amount >= 1) "###,###" else "0.###")
        val s = StockMarket.stock(symbol) ?: stock
        val commission = StockPlayerManager.commission(s, amount, leverage)
        val cost = StockPlayerManager.cost(s, amount)
        val autoClosePrices = marginCallAtPrice(stockPlayer.getBalance())

        return TagResolver.builder()
            .resolver(s.tagResolver())
            .resolver(TagResolver.resolver("amount", Tag.inserting(strip(MiniMessage.miniMessage().deserialize(decimalFormat.format(amount))) ?: Component.text(decimalFormat.format(amount)))))
            .resolver(TagResolver.resolver("type", Tag.inserting(strip(MiniMessage.miniMessage().deserialize(type.display)) ?: Component.text(type.display))))
            .resolver(TagResolver.resolver("symbol", Tag.inserting(strip(MiniMessage.miniMessage().deserialize(symbol)) ?: Component.text(symbol))))
            .resolver(TagResolver.resolver("leverage", Tag.inserting(Component.text(leverage))))
            .resolver(TagResolver.resolver("total_cost", Tag.inserting(mm(formatAmount(cost + commission), true))))
            .resolver(TagResolver.resolver("commission", Tag.inserting(mm(formatAmount(commission), true))))
            .resolver(TagResolver.resolver("cost", Tag.inserting(mm(formatAmount(cost), true))))
            .resolver(TagResolver.resolver("leveraged_price", Tag.inserting(mm(formatAmount(leverage * amount * s.price), true))))
            .resolver(TagResolver.resolver("max_buy_price", Tag.inserting(mm(formatAmount(StockConfig.maxBuyPrice), true))))
            .resolver(TagResolver.resolver("max_leveraged_price", Tag.inserting(mm(formatAmount(StockConfig.maxLeveragedPrice), true))))
            .resolver(TagResolver.resolver("upper", Tag.inserting(mm(if (upper > 1_000_000_000) "<red>Нет" else formatAmount(upper), true))))
            .resolver(TagResolver.resolver("lower", Tag.inserting(mm(if (lower > 1_000_000_000) "<red>Нет" else formatAmount(lower), true))))
            .resolver(TagResolver.resolver("close_at_low", Tag.inserting(mm(if (autoClosePrices.low == -1.0) "<red>Нет" else formatAmount(autoClosePrices.low), true))))
            .resolver(TagResolver.resolver("close_at_high", Tag.inserting(mm(if (autoClosePrices.high == -1.0) "<red>Нет" else formatAmount(autoClosePrices.high), true))))
            .resolver(TagResolver.resolver("balance", Tag.inserting(mm(formatAmount(stockPlayer.getBalance()), true))))
            .resolver(TagResolver.resolver("position_amount", Tag.inserting(mm(stockPlayer.positions().size.toString(), true))))
            .resolver(TagResolver.resolver("max_stock_amount", Tag.inserting(mm(stockPlayer.maxStockAmount().toString(), true))))
            .build()
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 1), pane)

        back = guiItem(Material.BLUE_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ PositionSelector(stockPlayer, symbol) }, click.whoClicked)
            }
            display("<gray>Назад")
            lore(emptyList())
            modelData(11013)
            fromConfig(StockConfig.config(), "locale.position-creator.back")
        }
        pane.addItem(back, 0, 0)
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        this.addPane(Slot.fromXY(0, 0), pane)
    }

    private fun getNewAmount(click: InventoryClickEvent): Double {
        val newAmount = amount
        val delta: Double
        if (click.isLeftClick) {
            delta = if (newAmount >= 1) {
                if (click.isShiftClick) { if (newAmount == 1.0) 9.0 else 10.0 } else 1.0
            } else {
                if (click.isShiftClick) min(1.0, newAmount + 0.1) - newAmount
                else min(1.0, newAmount + 0.01) - newAmount
            }
        } else if (click.isRightClick) {
            delta = if (newAmount > 1.0) {
                if (click.isShiftClick) max(1.0, newAmount - 10) - newAmount
                else max(1.0, newAmount - 1) - newAmount
            } else {
                if (click.isShiftClick) max(0.0, newAmount - 0.1) - newAmount
                else max(0.0, newAmount - 0.01) - newAmount
            }
        } else delta = 0.0
        var d = delta
        if (newAmount > 100) d *= 10
        if (newAmount > 1000) d *= 10
        if (newAmount > 100000) d *= 10
        return newAmount + d
    }

    private fun acceptAmountClick(click: InventoryClickEvent) {
        click.isCancelled = true
        amountTask?.takeUnless { it.isCancelled }?.cancel()
        val newAmount = getNewAmount(click)
        if (newAmount <= 0) return
        val price = newAmount * stock.price
        if (price > StockConfig.maxBuyPrice) {
            amountTask = GuiUtils.temporaryChange(
                amountItem.item,
                mm(StockConfig.string("position-creator.too-expensive-position").replace("<max_buy_price>", formatAmount(StockConfig.maxBuyPrice))),
                null, 100L, ::update
            )
            this.update()
            return
        }
        if (leverage * price > StockConfig.maxLeveragedPrice) {
            amountTask = GuiUtils.temporaryChange(
                amountItem.item,
                mm(StockConfig.string("position-creator.too-much-leverage").replace("<max_leveraged_price>", formatAmount(StockConfig.maxLeveragedPrice))),
                null, 100L, ::update
            )
            this.update()
            return
        }
        amount = newAmount
        updateItems()
    }

    private fun acceptTypeClick(click: InventoryClickEvent) {
        click.isCancelled = true
        type = if (type == Position.Type.BOUGHT) Position.Type.SHORTED else Position.Type.BOUGHT
        updateItems()
    }

    private fun acceptUpperClick(click: InventoryClickEvent) {
        click.isCancelled = true
        upper = getNewUpper(click)
        updateItems()
    }

    private fun acceptLowerClick(click: InventoryClickEvent) {
        click.isCancelled = true
        lower = getNewLower(click)
        updateItems()
    }

    private fun acceptCreateClick(click: InventoryClickEvent) {
        click.isCancelled = true
        val s = StockMarket.stock(symbol) ?: return
        val response = StockPlayerManager.economyCheck(stockPlayer, s, amount, leverage)
        if (!response.success) {
            var success = false
            if (stockPlayer.autoTake) {
                success = StockPlayerManager.addToTradingBalanceFromVault(stockPlayer, response.lack)
            }
            if (!success) {
                GuiUtils.temporaryChange(
                    createItem.item,
                    MiniMessage.miniMessage().deserialize(StockConfig.string("position-creator.create-no-money.display")),
                    StockConfig.stringList("position-creator.create-no-money.lore").map { MiniMessage.miniMessage().deserialize(it) },
                    100L, ::update
                )
                this.update()
                return
            }
        }
        (click.whoClicked as Player).performCommand(
            "arc-invest -t:${type.command} -s:$symbol -amount:$amount -leverage:$leverage -up:$upper -down:$lower"
        )
        PositionSelector(stockPlayer, symbol).show(click.whoClicked)
    }

    private fun acceptLeverageClick(click: InventoryClickEvent) {
        click.isCancelled = true
        val newLeverage = getNewLeverage(click)
        if (newLeverage * amount * stock.price > StockConfig.maxLeveragedPrice) {
            amountTask = GuiUtils.temporaryChange(
                amountItem.item,
                mm(StockConfig.string("position-creator.too-much-leverage").replace("<max_leveraged_price>", formatAmount(StockConfig.maxLeveragedPrice))),
                null, 100L, ::update
            )
            this.update()
            return
        }
        leverage = newLeverage
        updateItems()
    }

    private fun marginCallAtPrice(balance: Double): Position.AutoClosePrices {
        val bankruptPrice = stock.price - balance / amount / leverage
        val lowMarginCallPrice = if (lower > 1_000_000_000) -1.0 else stock.price - lower / amount / leverage
        val upperMarginCallPrice = if (upper > 1_000_000_000) -1.0 else stock.price + upper / amount / leverage
        var low = min(bankruptPrice, lowMarginCallPrice)
        if (low < 0) low = -1.0
        return Position.AutoClosePrices(low, upperMarginCallPrice)
    }

    private fun updateItems() {
        ARC.instance.server.scheduler.runTaskAsynchronously(ARC.instance, Runnable {
            val resolver = resolver(amount, type, leverage)

            leverageItem.item.itemMeta = itemStack(Material.LEVER) {
                tagResolver(resolver)
                display("                      <gold>Рычаг")
                lore(listOf(
                    "                    <gray><strikethrough>             ",
                    "",
                    "  <white>⚖ Рычаг: <#E91E63><leverage>  ",
                    "  <white>🛑 Максимальный рычаг: <#E91E63><max_leverage>  ",
                    "  <white>💹 Стоимость с учетом рычага: <#E91E63><leveraged_price><white>💰  ",
                    "  <white>🛑 Макс. стоимость с учетом рычага: <#E91E63><max_leveraged_price><white>💰  ",
                    "",
                    "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                fromConfig(StockConfig.config(), "locale.position-creator.leverage")
            }.itemMeta

            lowerItem.item.itemMeta = itemStack(Material.HONEY_BLOCK) {
                tagResolver(resolver)
                display("              <gold>Максимальные потери")
                lore(listOf(
                    "                      <gray><strikethrough>             ",
                    "",
                    "    <white>📈 Граница: <green><lower><white>💰  ",
                    "",
                    "    <gray>Позиция автоматически закроется при  ",
                    "    <gray>достижении <green>прибыли<gray> в указаную величину  ",
                    "",
                    "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                fromConfig(StockConfig.config(), "locale.position-creator.lower")
            }.itemMeta

            upperItem.item.itemMeta = itemStack(Material.SLIME_BLOCK) {
                tagResolver(resolver)
                display("              <gold>Максимальная прибыль")
                lore(listOf(
                    "                      <gray><strikethrough>             ",
                    "",
                    "    <white>📈 Граница: <green><upper><white>💰  ",
                    "",
                    "    <gray>Позиция автоматически закроется при  ",
                    "    <gray>достижении <green>прибыли<gray> в указаную величину  ",
                    "",
                    "             <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                fromConfig(StockConfig.config(), "locale.position-creator.upper")
            }.itemMeta

            typeItem.setItem(itemStack(if (type == Position.Type.BOUGHT) Material.LAPIS_LAZULI else Material.COAL) {
                tagResolver(resolver)
                display("                 <gold>Тип")
                lore(listOf(
                    "               <gray><strikethrough>         ",
                    "",
                    "            <white>🔥 <gray><type>",
                    "",
                    "   <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                fromConfig(StockConfig.config(), "locale.position-creator.type")
            })

            createItem.setItem(itemStack(Material.GREEN_STAINED_GLASS_PANE) {
                display("                       <gold>Создать")
                lore(listOf(
                    "                       <gray><strikethrough>           ",
                    "",
                    "  <white>🏁 Цена: <#4CAF50><cost><white>💰  ",
                    "  <white>🔥 Комиссия: <#E91E63><commission><white>💰  ",
                    "  <white>📈 Общая цена: <#FF9800><total_cost><white>💰  ",
                    "  <white>₪ Ваш баланс: <#2196F3><balance><white>💰  ",
                    "  <white>🛑 Авто закроется при падении до: <#F44336><close_at_low><white>💰  ",
                    "  <white>🛑 Авто закроется при росте до: <#9C27B0><close_at_high><white>💰  ",
                    "  ",
                    "  <white>🛒 У вас позиций: <#FFC107><position_amount>  ",
                    "  <white>🛑 Ваш лимит: <#E91E63><max_stock_amount>  ",
                    "",
                    "           <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                tagResolver(resolver)
                fromConfig(StockConfig.config(), "locale.position-creator.create")
            })

            amountItem.item.itemMeta = itemStack(Material.GOLD_INGOT) {
                tagResolver(resolver)
                display("                      <gold>Количество")
                lore(listOf(
                    "                       <gray><strikethrough>             ",
                    "",
                    "  <white>💼 Количество: <#2196F3><amount>  ",
                    "",
                    "  <white>💲 Цена:<#4CAF50> <cost><white>💰  ",
                    "  <white>🔥 Комиссия: <#4CAF50><commission><white>💰  ",
                    "  <white>📈 Общая цена: <#4CAF50><total_cost><white>💰  ",
                    "  <white>₪ Ваш баланс: <#2196F3><balance><white>💰  ",
                    "",
                    "  <white>📈 Верхняя граница маржи: <#E91E63><upper><white>💰  ",
                    "  <white>📉 Нижняя граница маржи: <#E91E63><lower><white>💰  ",
                    "  <white>🛑 Авто-закроется при падении цены до: <#E91E63><close_at_low><white>💰  ",
                    "  <white>🛑 Авто-закроется при росте цены до: <#E91E63><close_at_high><white>💰  ",
                    "",
                    "           <#8c8c8c>• <#92bed8>Нажмите <#e6fff3>чтобы изменить <#8c8c8c>•  ",
                    "",
                ))
                fromConfig(StockConfig.config(), "locale.position-creator.amount")
            }.itemMeta

            ARC.instance.server.scheduler.runTask(ARC.instance, Runnable { update() })
        })
    }
}
