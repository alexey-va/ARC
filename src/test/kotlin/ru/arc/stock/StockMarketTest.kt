package ru.arc.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import ru.arc.repository.CachedRepository
import ru.arc.repository.InMemoryStorage
import ru.arc.repository.InMemorySyncService
import ru.arc.repository.RepoConfig
import kotlin.time.Duration.Companion.seconds

class StockMarketTest : FreeSpec({

    fun makeRepo(): CachedRepository<Stock> {
        val storage = InMemoryStorage<Stock>()
        val sync = InMemorySyncService<Stock>()
        val config = RepoConfig.builder<Stock>("stocks-test")
            .saveInterval(10.seconds)
            .build()
        return CachedRepository(config = config, storage = storage, syncService = sync)
    }

    fun makeStock(symbol: String, price: Double = 100.0) = Stock(
        symbol = symbol,
        price = price,
        type = Stock.Type.STOCK,
    )

    fun stockMap(
        symbol: String,
        display: String = "Display",
        type: String = "STOCK",
    ) = mapOf(
        "symbol" to symbol,
        "display" to display,
        "lore" to listOf("<gray>Test stock"),
        "type" to type,
        "maxLeverage" to 100,
        "icon" to mapOf("material" to "PAPER", "data" to 0),
    )

    beforeEach {
        StockMarket.stockRepo = makeRepo()
    }

    afterEach {
        runTest { StockMarket.stockRepo.shutdown() }
    }

    "stock()" - {
        "returns null when symbol not in cache" {
            StockMarket.stock("AAPL").shouldBeNull()
        }

        "returns stock when present in cache" {
            runTest {
                StockMarket.stockRepo.save(makeStock("AAPL", 150.0))

                val result = StockMarket.stock("AAPL")

                result.shouldNotBeNull()
                result.symbol shouldBe "AAPL"
                result.price shouldBe 150.0
            }
        }
    }

    "stocks()" - {
        "returns empty collection when cache is empty" {
            StockMarket.stocks().shouldBeEmpty()
        }

        "returns all cached stocks" {
            runTest {
                StockMarket.stockRepo.save(makeStock("AAPL"))
                StockMarket.stockRepo.save(makeStock("GOOG"))
                StockMarket.stockRepo.save(makeStock("TSLA"))

                StockMarket.stocks() shouldHaveSize 3
            }
        }
    }

    "isEnabledStock()" - {
        "returns false for null stock" {
            StockMarket.isEnabledStock(null) shouldBe false
        }

        "returns false when symbol not in configStocks" {
            val stock = makeStock("UNKNOWN")
            StockMarket.isEnabledStock(stock) shouldBe false
        }

        "returns true after loading stock via loadStockFromMap" {
            val map = stockMap("AAPL")
            StockMarket.loadStockFromMap(map)

            StockMarket.isEnabledStock(makeStock("AAPL")) shouldBe true
        }
    }

    "loadStockFromMap()" - {
        "registers new stock in configStocks" {
            val map = stockMap("MSFT", display = "Microsoft")
            StockMarket.loadStockFromMap(map)

            StockMarket.isEnabledStock(makeStock("MSFT")) shouldBe true
        }

        "does not create stock in cache when not present" {
            StockMarket.loadStockFromMap(stockMap("MSFT", display = "Microsoft"))

            StockMarket.stock("MSFT").shouldBeNull()
        }

        "updates existing stock metadata in cache" {
            runTest {
                val existing = makeStock("GOOG", price = 2800.0)
                StockMarket.stockRepo.save(existing)

                StockMarket.loadStockFromMap(stockMap("GOOG", display = "Google", type = "STOCK"))

                val updated = StockMarket.stock("GOOG")
                updated.shouldNotBeNull()
                updated.display shouldBe "Google"
                updated.price shouldBe 2800.0
            }
        }

        "does not crash on malformed map" {
            StockMarket.loadStockFromMap(mapOf("bad" to "data"))
        }
    }
})
