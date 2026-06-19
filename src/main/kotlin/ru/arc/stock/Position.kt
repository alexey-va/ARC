package ru.arc.stock

import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.util.UUID

class Position(
    var symbol: String = "",
    var startPrice: Double = 0.0,
    var leverage: Double = 1.0,
    var upperBoundMargin: Double = 0.0,
    var lowerBoundMargin: Double = 0.0,
    var commission: Double = 0.0,
    var timestamp: Long = 0L,
    var positionUuid: UUID = UUID.randomUUID(),
    var type: Type = Type.BOUGHT,
    var amount: Double = 0.0,
    var iconMaterial: Material = Material.PAPER,
    var receivedDividend: Double = 0.0,
) {

    fun gains(currentPrice: Double): Double =
        (if (type == Type.BOUGHT) 1 else -1) * (currentPrice - startPrice) * amount * leverage

    fun gains(): Double {
        val price = getStockPrice()
        return if (price == 0.0) 0.0 else gains(price)
    }

    fun marginCall(currentPrice: Double): Int {
        val gains = gains(currentPrice)
        return when {
            gains > upperBoundMargin -> 1
            gains < 0 && -gains > lowerBoundMargin -> -1
            else -> 0
        }
    }

    fun getStock(): Stock? = StockMarket.stock(symbol)

    fun totalValue(): Double {
        val stock = getStock() ?: return 0.0
        return gains(stock.price) * (leverage - 1) + amount * stock.price
    }

    fun marginCallAtPrice(balance: Double, isAutoTake: Boolean): AutoClosePrices {
        val bankruptPrice = startPrice - balance / amount / leverage
        val lowMarginCallPrice = if (lowerBoundMargin > 1_000_000_000) -1.0 else startPrice - lowerBoundMargin / amount / leverage
        val upperMarginCallPrice = if (upperBoundMargin > 1_000_000_000) -1.0 else startPrice + upperBoundMargin / amount / leverage
        var low = minOf(bankruptPrice, lowMarginCallPrice)
        if (low < 0) low = -1.0
        return AutoClosePrices(low, upperMarginCallPrice)
    }

    fun resolver(): TagResolver {
        val timestampInstant = Instant.ofEpochMilli(timestamp)
        val timePassed = Duration.between(timestampInstant, Instant.now())
        val stock = getStock()
        val currentPrice = stock?.price ?: 0.0
        val gains = gains(currentPrice)
        return TagResolver.builder()
            .resolver(TagResolver.resolver("amount", Tag.inserting(mm(formatAmount(amount), true))))
            .resolver(TagResolver.resolver("dividend_amount", Tag.inserting(mm(formatAmount(amount * (stock?.dividend ?: 0.0)), true))))
            .resolver(TagResolver.resolver("position_gains", Tag.inserting(mm(formatAmount(gains), true))))
            .resolver(TagResolver.resolver("symbol", Tag.inserting(mm(symbol, true))))
            .resolver(TagResolver.resolver("total_position_gains", Tag.inserting(mm(formatAmount(gains - commission), true))))
            .resolver(TagResolver.resolver("total_gains_with_dividends", Tag.inserting(mm(formatAmount(gains - commission + receivedDividend), true))))
            .resolver(TagResolver.resolver("type", Tag.inserting(mm(type.display, true))))
            .resolver(TagResolver.resolver("buy_price", Tag.inserting(mm(formatAmount(commission + amount * startPrice), true))))
            .resolver(TagResolver.resolver("uuid", Tag.inserting(mm(positionUuid.toString().split("-")[0], true))))
            .resolver(TagResolver.resolver("leverage", Tag.inserting(mm(formatAmount(leverage), true))))
            .resolver(TagResolver.resolver("leveraged_price", Tag.inserting(mm(formatAmount(leverage * amount * startPrice), true))))
            .resolver(TagResolver.resolver("stock_price", Tag.inserting(mm(formatAmount(getStockPrice()), true))))
            .resolver(TagResolver.resolver("received_dividend", Tag.inserting(mm(formatAmount(receivedDividend), true))))
            .resolver(TagResolver.resolver("dividend", Tag.inserting(mm(formatAmount(stock?.dividend ?: 0.0), true))))
            .resolver(TagResolver.resolver("upper", Tag.inserting(
                if (upperBoundMargin > 1_000_000_000) mm("<red>Нет")
                else mm(formatAmount(upperBoundMargin), true)
            )))
            .resolver(TagResolver.resolver("lower", Tag.inserting(
                if (lowerBoundMargin > 1_000_000_000) mm("<red>Нет")
                else mm(formatAmount(lowerBoundMargin), true)
            )))
            .resolver(TagResolver.resolver("starting_price", Tag.inserting(mm(formatAmount(startPrice), true))))
            .resolver(TagResolver.resolver("commission", Tag.inserting(mm(formatAmount(commission), true))))
            .resolver(TagResolver.resolver("hours_since_bought", Tag.inserting(mm("${timePassed.toHours()}", true))))
            .build()
    }

    private fun getStockPrice(): Double {
        val stock = StockMarket.stock(symbol) ?: run {
            println("Could not find: $symbol")
            return 0.0
        }
        return stock.price
    }

    fun bankrupt(currentPrice: Double, currentBalance: Double): BankruptResponse {
        val gains = gains(currentPrice)
        return if (gains + currentBalance >= 0) BankruptResponse(false, gains + currentBalance)
        else BankruptResponse(true, gains + currentBalance)
    }

    data class BankruptResponse(val bankrupt: Boolean, val total: Double)
    data class AutoClosePrices(val low: Double, val high: Double)

    enum class Type(val display: String, val command: String) {
        BOUGHT("<green>Покупка", "buy"),
        SHORTED("<red>Шорт", "short"),
    }
}
