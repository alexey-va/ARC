package ru.arc.stock

import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.board.ItemIcon
import ru.arc.config.StockConfig
import ru.arc.repository.Entity
import java.time.Duration
import java.time.Instant

import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm

class Stock(
    var symbol: String = "",
    var price: Double = 0.0,
    var dividend: Double = 0.0,
    var lastUpdated: Long = 0L,
    var display: String = "",
    var lore: List<String> = emptyList(),
    var icon: ItemIcon? = null,
    var lastTimeDividend: Long = 0L,
    var maxLeverage: Int = 10000,
    var type: Type = Type.STOCK,
) : Entity {

    fun tagResolver(): TagResolver {
        val hours = Duration.between(Instant.ofEpochMilli(lastTimeDividend), Instant.now()).toHours().toInt()
        val dividendPeriodHours = (StockConfig.dividendPeriod / 60L / 60L).toInt()
        val hoursTill = dividendPeriodHours - hours
        val low = HistoryManager.low(symbol)
        val high = HistoryManager.high(symbol)
        val volatility = (high - low) / price
        val volatilityString = when {
            volatility < 0.02 -> "<dark_green>Низкие"
            volatility < 0.04 -> "<green>Небольшие"
            volatility < 0.06 -> "<yellow>Значительные"
            volatility < 0.08 -> "<red>Высокие"
            else -> "<dark_red>Импульсивные"
        }
        return TagResolver.builder()
            .resolver(TagResolver.resolver("stock_price", Tag.inserting(mm(formatAmount(price, 5), true))))
            .resolver(TagResolver.resolver("max_leverage", Tag.inserting(mm(formatAmount(maxLeverage.toDouble()), true))))
            .resolver(TagResolver.resolver("lowest_recent_price",
                Tag.inserting(mm(if (low == 0.0) "<red>Нет" else formatAmount(low), true))))
            .resolver(TagResolver.resolver("highest_recent_price",
                Tag.inserting(mm(if (high == 0.0) "<red>Нет" else formatAmount(high), true))))
            .resolver(TagResolver.resolver("volatility", Tag.inserting(mm(volatilityString, true))))
            .resolver(TagResolver.resolver("hours_since_dividend", Tag.inserting(mm("$hours", true))))
            .resolver(TagResolver.resolver("hours_till_dividend", Tag.inserting(mm("${maxOf(0, hoursTill)}", true))))
            .resolver(TagResolver.resolver("dividends_period_hours", Tag.inserting(mm("$dividendPeriodHours", true))))
            .resolver(TagResolver.resolver("stock_dividend", Tag.inserting(mm(formatAmount(dividend), true))))
            .build()
    }

    override fun id(): String = symbol

    enum class Type {
        STOCK, CURRENCY, CRYPTO, COMMODITY
    }
}
