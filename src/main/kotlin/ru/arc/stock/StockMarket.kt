package ru.arc.stock

import ru.arc.configs.StockConfig
import ru.arc.repository.CachedRepository
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

object StockMarket {

    private val configStocks = mutableMapOf<String, ConfigStock>()
    lateinit var stockRepo: CachedRepository<Stock>
    private var _client: StockClient? = null

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
        _client = stockClient
    }

    @JvmStatic
    fun loadStockFromMap(map: Map<*, *>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val stock = ConfigStock.deserialize(map as Map<String, Any>)
            stock.symbol = stock.symbol.uppercase()
            configStocks[stock.symbol] = stock

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
        val client = _client ?: return

        val updates = mutableMapOf<String, Double>()
        var fetchedCrypto = false

        for ((symbol, configStock) in configStocks) {
            try {
                val current = stockRepo.getNow(symbol)
                val lastUpdated = current?.lastUpdated ?: 0L
                if (System.currentTimeMillis() - lastUpdated > StockConfig.stockRefreshRate * 1000L) {
                    if (configStock.type == Stock.Type.CRYPTO) {
                        if (fetchedCrypto) continue
                        updates.putAll(client.cryptoPrices())
                        fetchedCrypto = true
                        continue
                    }
                    updates[symbol] = client.price(configStock)
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
                    current.dividend = current.price * StockConfig.dividendPercentFromPrice
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
        stocks()
            .filter { it.dividend > 0.000001 }
            .filter { System.currentTimeMillis() - it.lastTimeDividend >= 23 * 60 * 60 * 1000L }
            .forEach { stock ->
                stock.lastTimeDividend = System.currentTimeMillis()
                stockRepo.markDirty(stock)
                StockPlayerManager.giveDividend(stock.symbol)
            }
    }

    fun saveHistory() {
        info("Saving stock history")
        HistoryManager.saveHistory()
    }
}
