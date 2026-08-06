package ru.arc.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import ru.arc.board.ItemIcon

class StockClientTest : FreeSpec({
    "does not create a stream without a configured API key" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient(null, null, factory)

        client.startWebSocket(listOf("AAPL"))

        factory.streams.size shouldBe 0
        client.hasWebSocketClient() shouldBe false
        client.isClosed shouldBe true
    }

    "treats a blank API key as missing" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("   ", "   ", factory)

        client.startWebSocket(listOf("AAPL"))

        factory.streams.size shouldBe 0
        client.isClosed shouldBe true
    }

    "starts one stream and ignores duplicate start while it is running" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("key", null, factory)

        client.startWebSocket(listOf("AAPL", "AAPL", "MSFT"))
        client.startWebSocket(listOf("GOOG"))

        factory.streams.size shouldBe 1
        factory.latest.startedSymbols shouldContainExactly listOf("AAPL", "MSFT")
        client.hasWebSocketClient() shouldBe true
        client.isClosed shouldBe false
        client.close()
    }

    "ignores a late price from the replaced stream" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("key", null, factory)
        client.startWebSocket(listOf("AAPL"))
        val replaced = factory.latest
        replaced.disconnect()
        client.startWebSocket(listOf("AAPL"))

        replaced.emit("AAPL", 100.0)
        factory.latest.emit("AAPL", 20.0)

        client.price(stock("AAPL")) shouldBeExactly 20.0
        client.close()
    }

    "replaces and closes a disconnected stream" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("key", null, factory)
        client.startWebSocket(listOf("AAPL"))
        val first = factory.latest
        first.disconnect()

        client.startWebSocket(listOf("MSFT"))

        first.closeCalls shouldBe 1
        factory.streams.size shouldBe 2
        factory.latest.startedSymbols shouldContainExactly listOf("MSFT")
        client.isClosed shouldBe false
        client.close()
    }

    "close is idempotent and releases the active stream" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("key", null, factory)
        client.startWebSocket(listOf("AAPL"))
        val stream = factory.latest

        client.close()
        client.close()

        stream.closeCalls shouldBe 1
        client.hasWebSocketClient() shouldBe false
        client.isClosed shouldBe true
    }

    "StockMarket closes the previous client when configuration replaces it" {
        val firstFactory = FakeStockPriceStreamFactory()
        val first = StockClient("first", null, firstFactory)
        first.startWebSocket(listOf("AAPL"))
        val secondFactory = FakeStockPriceStreamFactory()
        val second = StockClient("second", null, secondFactory)
        second.startWebSocket(listOf("MSFT"))

        StockMarket.setClient(first)
        StockMarket.setClient(second)

        firstFactory.latest.closeCalls shouldBe 1
        secondFactory.latest.closeCalls shouldBe 0
        StockMarket.closeClient()
        secondFactory.latest.closeCalls shouldBe 1
    }

    "averages all buffered prices for a symbol" {
        val factory = FakeStockPriceStreamFactory()
        val client = StockClient("key", null, factory)
        client.startWebSocket(listOf("AAPL"))
        factory.latest.emit("AAPL", 10.0)
        factory.latest.emit("AAPL", 14.0)

        client.price(stock("AAPL")) shouldBeExactly 12.0
        client.close()
    }

    "Finnhub parser keeps every valid price in a batch" {
        FinnhubMessageParser.parse(
            """{"data":[{"s":"AAPL","p":10.5},{"s":"MSFT","p":20},{"s":"bad"}]}""",
        ) shouldContainExactly
            listOf(
                "AAPL" to 10.5,
                "MSFT" to 20.0,
            )
    }

    "Finnhub parser ignores malformed payloads" {
        FinnhubMessageParser.parse("not-json") shouldBe emptyList()
        FinnhubMessageParser.parse("""{"type":"ping"}""") shouldBe emptyList()
    }

    "Yahoo Finance parser reads the regular market price" {
        parseYahooRegularMarketPrice(
            """{"chart":{"result":[{"meta":{"symbol":"BZ=F","regularMarketPrice":91.93}}]}}""",
        ) shouldBe 91.93
    }

    "Yahoo Finance parser rejects incomplete and malformed payloads" {
        parseYahooRegularMarketPrice("""{"chart":{"result":[]}}""") shouldBe null
        parseYahooRegularMarketPrice("not-json") shouldBe null
    }

    "Finnhub REST authentication keeps the API key out of the request URI" {
        val request = finnhubQuoteRequest("AAPL", "test-secret")

        request.uri.toString() shouldBe "https://finnhub.io/api/v1/quote?symbol=AAPL"
        request.uri.toString() shouldNotContain "test-secret"
        request.headers shouldBe mapOf("X-Finnhub-Token" to "test-secret")
    }
})

private fun stock(symbol: String): ConfigStock =
    ConfigStock(
        symbol = symbol,
        display = symbol,
        lore = emptyList(),
        icon = mockk<ItemIcon>(),
        maxLeverage = 1,
        type = Stock.Type.STOCK,
    )

private class FakeStockPriceStreamFactory : StockPriceStreamFactory {
    val streams = mutableListOf<FakeStockPriceStream>()
    val latest: FakeStockPriceStream
        get() = streams.last()

    override fun create(
        apiKey: String,
        onPrice: (String, Double) -> Unit,
    ): StockPriceStream =
        FakeStockPriceStream(onPrice).also(streams::add)
}

private class FakeStockPriceStream(
    private val onPrice: (String, Double) -> Unit,
) : StockPriceStream {
    var startedSymbols: List<String> = emptyList()
    var closeCalls: Int = 0
    private var running = false

    override val isRunning: Boolean
        get() = running

    override fun start(symbols: Collection<String>) {
        startedSymbols = symbols.toList()
        running = true
    }

    fun emit(symbol: String, price: Double) {
        onPrice(symbol, price)
    }

    fun disconnect() {
        running = false
    }

    override fun close() {
        closeCalls++
        running = false
    }
}
