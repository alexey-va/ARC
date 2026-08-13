package ru.arc.stock

import ru.arc.config.StockConfig
import ru.arc.repository.CachedRepository
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.concurrent.ConcurrentHashMap

object StockMarket {

    private val configStocks = ConcurrentHashMap<String, ConfigStock>()
    lateinit var stockRepo: CachedRepository<Stock>
    @Volatile
    private var client: StockClient? = null

    @JvmStatic fun stock(symbol: String): Stock? = stockRepo.getNow(symbol)

    @JvmStatic fun stocks(): Collection<Stock> = stockRepo.allNow()

    @JvmStatic fun configStocks(): Collection<ConfigStock> = configStocks.values

    @JvmStatic
    fun isEnabledStock(stock: Stock?): Boolean {
        if (stock == null) return false
        return configStocks.containsKey(stock.symbol)
    }

    @JvmStatic
    fun setClient(stockClient: StockClient) {
        val previous =
            synchronized(this) {
                client.also { client = stockClient }
            }
        if (previous !== stockClient) previous?.close()
    }

    @JvmStatic
    fun closeClient() {
        val previous =
            synchronized(this) {
                client.also { client = null }
            }
        previous?.close()
    }

    internal fun resetConfiguration() {
        configStocks.clear()
        closeClient()
    }

    @JvmStatic
    fun loadStockFromMap(map: Map<*, *>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val stock = ConfigStock.deserialize(map as Map<String, Any>)
            stock.symbol = stock.symbol.uppercase()
            configStocks[stock.symbol] = stock

            if (!::stockRepo.isInitialized) return
            val current = stockRepo.getNow(stock.symbol) ?: return
            current.lore = stock.lore
            current.display = stock.display
            current.icon = stock.icon
            current.maxLeverage = stock.maxLeverage
            current.type = stock.type
            stockRepo.markDirty(current)
        } catch (e: Exception) {
            error("Error loading stock from map: {}", map, e)
        }
    }

    suspend fun updateStocks() {
        if (!StockConfig.mainServer) return
        val activeClient = client ?: return

        val updates = mutableMapOf<String, Double>()
        var fetchedCrypto = false

        for ((symbol, configStock) in configStocks) {
            try {
                val current = stockRepo.getNow(symbol)
                val lastUpdated = current?.lastUpdated ?: 0L
                if (System.currentTimeMillis() - lastUpdated > StockConfig.stockRefreshRate * 1000L) {
                    if (configStock.type == Stock.Type.CRYPTO) {
                        if (fetchedCrypto) continue
                        updates.putAll(activeClient.cryptoPrices())
                        fetchedCrypto = true
                        continue
                    }
                    updates[symbol] = activeClient.price(configStock)
                }
            } catch (e: Exception) {
                error("Error fetching data for: {}", symbol, e)
            }
        }

        for ((symbol, rawPrice) in updates) {
            try {
                val upperSymbol = symbol.uppercase()
                val configStock = configStocks[upperSymbol] ?: continue
                val current = stockRepo.getOrCreate(upperSymbol) {
                    configStock.toStock(rawPrice, 0.0, System.currentTimeMillis(), 0)
                }.getOrNull() ?: continue

                var price = rawPrice
                if (price < 0 || price > 1_000_000) {
                    if (current.price < 0 || current.price > 1_000_000) {
                        error("Price for $upperSymbol is invalid: $price")
                        continue
                    }
                    price = current.price
                }

                HistoryManager.add(upperSymbol, price)
                current.price = price
                current.lastUpdated = System.currentTimeMillis()
                if (current.type == Stock.Type.STOCK) {
                    current.dividend = effectiveDividendPerShare(current)
                    if (current.dividend > 10_000) {
                        error("Dividend for $upperSymbol is invalid: ${current.dividend}")
                        current.dividend = 0.0
                    }
                }
                stockRepo.markDirty(current)

                StockPlayerManager.updateAllPositionsOf(upperSymbol)
            } catch (e: Exception) {
                error("Error updating stock: {}", symbol, e)
            }
        }
    }

    fun payDividends() {
        if (!StockConfig.mainServer) return
        val now = System.currentTimeMillis()
        stocks()
            .filter { effectiveDividendPerShare(it) > 0.000001 }
            .filter { isDividendDue(it, now) }
            .forEach { stock ->
                stock.lastTimeDividend = now
                stockRepo.markDirty(stock)
                StockPlayerManager.giveDividend(stock.symbol)
            }
    }

    internal fun effectiveDividendRate(): Double {
        val configured = StockConfig.dividendPercentFromPrice
        val maximum = StockConfig.maxDividendPercentFromPrice.coerceAtMost(ABSOLUTE_MAX_DIVIDEND_RATE)
        if (!configured.isFinite() || !maximum.isFinite()) return 0.0
        return configured.coerceIn(0.0, maximum.coerceAtLeast(0.0))
    }

    internal fun effectiveDividendPeriodSeconds(): Long =
        StockConfig.dividendPeriod.coerceAtLeast(MINIMUM_DIVIDEND_PERIOD_SECONDS)

    internal fun effectiveDividendPerShare(stock: Stock): Double {
        val dividend = stock.price * effectiveDividendRate()
        return if (dividend.isFinite() && dividend > 0.0) dividend else 0.0
    }

    internal fun effectiveMaxLeverage(stock: Stock): Int = stock.maxLeverage.coerceIn(1, MAX_NEW_ORDER_LEVERAGE)

    internal fun effectiveMaxBuyPrice(): Double =
        finitePositiveLimit(StockConfig.maxBuyPrice, MAX_NEW_ORDER_PRINCIPAL)

    internal fun effectiveMaxLeveragedPrice(): Double =
        finitePositiveLimit(StockConfig.maxLeveragedPrice, MAX_NEW_ORDER_EXPOSURE)

    internal fun isDividendDue(stock: Stock, now: Long): Boolean =
        now - stock.lastTimeDividend >= effectiveDividendPeriodSeconds() * 1_000L

    private fun finitePositiveLimit(configured: Double, absolute: Double): Double =
        if (configured.isFinite() && configured > 0.0) configured.coerceAtMost(absolute) else absolute

    fun saveHistory() {
        info("Saving stock history")
        HistoryManager.saveHistory()
    }

    private const val MINIMUM_DIVIDEND_PERIOD_SECONDS = 24L * 60L * 60L
    private const val ABSOLUTE_MAX_DIVIDEND_RATE = 0.0002
    private const val MAX_NEW_ORDER_LEVERAGE = 10
    private const val MAX_NEW_ORDER_PRINCIPAL = 100_000.0
    private const val MAX_NEW_ORDER_EXPOSURE = 1_000_000.0
}
