package ru.arc.stock

import com.google.gson.reflect.TypeToken
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jsoup.Jsoup
import ru.arc.util.Common
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("UNCHECKED_CAST")
class StockClient(
    private val finnApiKey: String?,
    private val polyApiKey: String?,
) {

    companion object {
        var webSocketClient: WebSocketClient? = null
        @Volatile var isClosed = true
        val prices = ConcurrentHashMap<String, MutableList<Double>>()
        private val gson = Common.gson
        private val service = Executors.newSingleThreadExecutor()

        @JvmStatic
        fun stopClient() {
            val wsc = webSocketClient ?: return
            if (!isClosed) {
                try {
                    wsc.stop()
                    isClosed = true
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
    }

    fun cryptoPrices(): Map<String, Double> {
        val ids = StockMarket.configStocks()
            .filter { it.type == Stock.Type.CRYPTO }
            .joinToString("%2C") { it.symbol }
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd&precision=full"
        debug("Fetching crypto prices from {}", url)
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            val reader = InputStreamReader(connection.inputStream)
            val typeToken = object : TypeToken<Map<String, Map<String, Double>>>() {}
            val map: Map<String, Map<String, Double>> = gson.fromJson(reader, typeToken)
            val result = HashMap<String, Double>()
            map.forEach { (key, value) -> result[key] = value["usd"] ?: 0.0 }
            debug("Fetched crypto prices: {}", result)
            result
        } catch (e: Exception) {
            error("Could not load crypto prices", e)
            emptyMap()
        }
    }

    private fun fetchInvesting(url: String): Double {
        return try {
            val response = Jsoup.connect(url).execute()
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                warn("investing.com returned status {}", response.statusCode())
                return -1.0
            }
            val document = response.parse()
            val divElement = document.select("div[data-test=instrument-price-last]").first() ?: return -1.0
            divElement.text().replace(".", "").replace(",", ".").toDouble()
        } catch (e: Exception) {
            error("Could not load price from investing.com", e)
            -1.0
        }
    }

    fun startWebSocket(symbols: Collection<String>) {
        try {
            val client = MySocket()
            webSocketClient = WebSocketClient()

            val serverUri = "wss://ws.finnhub.io?token=cn7rbt9r01qplv1e8j50cn7rbt9r01qplv1e8j5g"
            val timeoutSeconds = 5L

            val threadPool = QueuedThreadPool(30, 1, 60000)
            webSocketClient!!.executor = threadPool
            webSocketClient!!.start()
            webSocketClient!!.connect(client, URI(serverUri))

            isClosed = false
            service.submit {
                try {
                    if (client.latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                        symbols.forEach { s ->
                            try {
                                client.session.remote.sendString("""{"type":"subscribe","symbol":"$s"}""")
                                Thread.sleep(100)
                            } catch (e: Exception) {
                                error("Could not send message to server", e)
                                throw RuntimeException(e)
                            }
                        }
                    } else {
                        isClosed = true
                        warn("WebSocket connection could not be established within {} seconds", timeoutSeconds)
                    }
                } catch (e: Exception) {
                    isClosed = true
                }
            }
        } catch (e: Exception) {
            isClosed = true
        }
    }

    private fun getStockPrice(stock: ConfigStock): Double {
        if (webSocketClient == null || !webSocketClient!!.isRunning || isClosed) {
            info("WebSocket is closed, reconnecting...")
            startWebSocket(StockMarket.stocks().filter { it.type == Stock.Type.STOCK }.map { it.symbol })
        }
        val list = prices.remove(stock.symbol)
        if (list == null) return fetchFinnhub(stock.symbol)
        return list.stream().mapToDouble { it }.average().orElseGet { fetchFinnhub(stock.symbol) }
    }

    private fun getCurrencyPrice(stock: ConfigStock): Double {
        val url = "https://ru.investing.com/currencies/${stock.symbol.replace("/", "-").lowercase()}"
        return fetchInvesting(url)
    }

    private fun getCommodityPrice(stock: ConfigStock): Double {
        val url = "https://ru.investing.com/commodities/${stock.symbol.replace("/", "-").lowercase()}"
        return fetchInvesting(url)
    }

    fun price(stock: ConfigStock): Double = when (stock.type) {
        Stock.Type.STOCK -> getStockPrice(stock)
        Stock.Type.CURRENCY -> getCurrencyPrice(stock)
        Stock.Type.COMMODITY -> getCommodityPrice(stock)
        else -> -1.0
    }

    private fun fetchFinnhub(symbol: String): Double {
        if (finnApiKey == null) return -1.0
        val url = "https://finnhub.io/api/v1/quote?symbol=$symbol&token=$finnApiKey"
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            val map = gson.fromJson(InputStreamReader(connection.inputStream), Map::class.java) as Map<String, Any>
            (map["c"] as Number).toDouble()
        } catch (e: Exception) {
            error("Could not load price from finnhub for {}", symbol, e)
            -1.0
        }
    }

    fun dividend(symbol: String): Double {
        if (polyApiKey == null) return 0.0
        val url = "https://api.polygon.io/v3/reference/dividends?ticker=$symbol&apiKey=$polyApiKey"
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            val map = gson.fromJson(InputStreamReader(connection.inputStream), Map::class.java) as Map<String, Any>
            ((map["results"] as List<Any>).first() as Map<String, Any>)["cash_amount"] as Double
        } catch (e: Exception) {
            0.0
        }
    }

    class MySocket : WebSocketAdapter() {
        val latch = CountDownLatch(1)

        override fun onWebSocketConnect(sess: Session) {
            super.onWebSocketConnect(sess)
            latch.countDown()
        }

        override fun onWebSocketText(message: String) {
            super.onWebSocketText(message)
            try {
                val map = gson.fromJson(message, Map::class.java) as Map<String, Any>
                val data = (map["data"] as List<Any>).first() as Map<String, Any>
                val price = (data["p"] as Number).toDouble()
                val symbol = data["s"] as String
                prices.getOrPut(symbol) { ArrayList() }.add(price)
            } catch (e: Exception) {
                // ignore malformed messages
            }
        }

        override fun onWebSocketClose(statusCode: Int, reason: String) {
            super.onWebSocketClose(statusCode, reason)
            info("WebSocket closed: {} - {}", statusCode, reason)
            isClosed = true
        }

        override fun onWebSocketError(cause: Throwable) {
            super.onWebSocketError(cause)
        }
    }
}
