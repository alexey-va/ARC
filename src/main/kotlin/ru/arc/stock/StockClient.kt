package ru.arc.stock

import com.google.gson.reflect.TypeToken
import org.jsoup.Jsoup
import ru.arc.util.Common
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("UNCHECKED_CAST")
class StockClient internal constructor(
    finnApiKey: String?,
    polyApiKey: String?,
    private val streamFactory: StockPriceStreamFactory,
) : AutoCloseable {
    constructor(
        finnApiKey: String?,
        polyApiKey: String?,
    ) : this(
        finnApiKey = finnApiKey,
        polyApiKey = polyApiKey,
        streamFactory =
            StockPriceStreamFactory { apiKey, onPrice ->
                JettyStockPriceStream(apiKey, onPrice)
            },
    )

    private val finnApiKey = finnApiKey?.takeIf { it.isNotBlank() }
    private val polyApiKey = polyApiKey?.takeIf { it.isNotBlank() }
    private val prices = ConcurrentHashMap<String, CopyOnWriteArrayList<Double>>()
    private var stream: StockPriceStream? = null
    @Volatile
    private var lifecycleVersion = 0L

    companion object {
        private const val HTTP_TIMEOUT_MS = 10_000
        private const val HTTP_USER_AGENT = "RusCrafting-ARC/1.0"
        private val YAHOO_TICKERS = mapOf("XBR/USD" to "BZ=F")
        private val gson = Common.gson
    }

    internal val isClosed: Boolean
        @Synchronized get() = stream?.isRunning != true

    @Synchronized
    internal fun hasWebSocketClient(): Boolean = stream != null

    fun cryptoPrices(): Map<String, Double> {
        val ids =
            StockMarket.configStocks()
                .filter { it.type == Stock.Type.CRYPTO }
                .joinToString("%2C") { it.symbol }
        if (ids.isEmpty()) return emptyMap()
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd&precision=full"
        debug("Fetching crypto prices from {}", url)
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            val typeToken = object : TypeToken<Map<String, Map<String, Double>>>() {}
            val map: Map<String, Map<String, Double>> =
                InputStreamReader(connection.inputStream).use { reader ->
                    gson.fromJson(reader, typeToken)
                }
            buildMap {
                map.forEach { (key, value) -> put(key, value["usd"] ?: 0.0) }
            }.also { result ->
                debug("Fetched crypto prices: {}", result)
            }
        } catch (e: Exception) {
            error("Could not load crypto prices", e)
            emptyMap()
        }
    }

    private fun fetchInvesting(url: String): Double =
        try {
            val response = Jsoup.connect(url).timeout(HTTP_TIMEOUT_MS).execute()
            if (response.statusCode() !in 200..299) {
                warn("investing.com returned status {}", response.statusCode())
                -1.0
            } else {
                response.parse()
                    .select("div[data-test=instrument-price-last]")
                    .first()
                    ?.text()
                    ?.replace(".", "")
                    ?.replace(",", ".")
                    ?.toDoubleOrNull()
                    ?: -1.0
            }
        } catch (e: Exception) {
            error("Could not load price from investing.com", e)
            -1.0
        }

    fun startWebSocket(symbols: Collection<String>) {
        val apiKey = finnApiKey ?: return
        val normalizedSymbols =
            symbols.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
        if (normalizedSymbols.isEmpty()) return
        val startVersion: Long
        val previous: StockPriceStream?
        synchronized(this) {
            if (stream?.isRunning == true) return
            previous = stream
            stream = null
            prices.clear()
            startVersion = ++lifecycleVersion
        }
        previous?.close()

        val created =
            try {
                streamFactory.create(
                    apiKey,
                    { symbol, price -> recordPrice(startVersion, symbol, price) },
                )
            } catch (e: Exception) {
                error("Could not create stock WebSocket", e)
                return
            }
        try {
            created.start(normalizedSymbols)
            val installed =
                synchronized(this) {
                    if (lifecycleVersion == startVersion && stream == null && created.isRunning) {
                        stream = created
                        true
                    } else {
                        false
                    }
                }
            if (!installed) created.close()
        } catch (e: Exception) {
            error("Could not start stock WebSocket", e)
            created.close()
        }
    }

    private fun recordPrice(version: Long, symbol: String, price: Double) {
        synchronized(this) {
            if (lifecycleVersion != version) return
            prices.computeIfAbsent(symbol) { CopyOnWriteArrayList() }.add(price)
        }
    }

    override fun close() {
        val current =
            synchronized(this) {
                lifecycleVersion++
                prices.clear()
                stream.also { stream = null }
            }
        current?.close()
    }

    private fun getStockPrice(stock: ConfigStock): Double {
        if (finnApiKey == null) return -1.0
        if (isClosed) {
            info("WebSocket is closed, reconnecting...")
            startWebSocket(
                StockMarket.stocks()
                    .filter { it.type == Stock.Type.STOCK }
                    .map { it.symbol },
            )
        }
        val buffered = prices.remove(stock.symbol) ?: return fetchFinnhub(stock.symbol)
        return buffered.stream().mapToDouble { it }.average().orElseGet { fetchFinnhub(stock.symbol) }
    }

    private fun getCurrencyPrice(stock: ConfigStock): Double {
        YAHOO_TICKERS[stock.symbol]?.let { ticker ->
            return fetchYahooFinance(ticker)
        }
        val url = "https://ru.investing.com/currencies/${stock.symbol.replace("/", "-").lowercase()}"
        return fetchInvesting(url)
    }

    private fun getCommodityPrice(stock: ConfigStock): Double {
        val url = "https://ru.investing.com/commodities/${stock.symbol.replace("/", "-").lowercase()}"
        return fetchInvesting(url)
    }

    fun price(stock: ConfigStock): Double =
        when (stock.type) {
            Stock.Type.STOCK -> getStockPrice(stock)
            Stock.Type.CURRENCY -> getCurrencyPrice(stock)
            Stock.Type.COMMODITY -> getCommodityPrice(stock)
            else -> -1.0
        }

    private fun fetchYahooFinance(ticker: String): Double {
        val encodedTicker = URLEncoder.encode(ticker, StandardCharsets.UTF_8)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encodedTicker?interval=1d&range=1d"
        return try {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", HTTP_USER_AGENT)
            val body =
                InputStreamReader(connection.inputStream).use { reader ->
                    reader.readText()
                }
            parseYahooRegularMarketPrice(body) ?: run {
                warn("Yahoo Finance returned no market price for {}", ticker)
                -1.0
            }
        } catch (e: Exception) {
            error("Could not load price from Yahoo Finance for {}", ticker, e)
            -1.0
        }
    }

    private fun fetchFinnhub(symbol: String): Double {
        if (finnApiKey == null) return -1.0
        val request = finnhubQuoteRequest(symbol, finnApiKey)
        return try {
            val connection = request.uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            request.headers.forEach(connection::setRequestProperty)
            val map =
                InputStreamReader(connection.inputStream).use { reader ->
                    gson.fromJson(reader, Map::class.java) as Map<String, Any>
                }
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
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            val map =
                InputStreamReader(connection.inputStream).use { reader ->
                    gson.fromJson(reader, Map::class.java) as Map<String, Any>
                }
            val results = map["results"] as? List<*> ?: return 0.0
            val first = results.firstOrNull() as? Map<*, *> ?: return 0.0
            (first["cash_amount"] as? Number)?.toDouble() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }
}

internal data class FinnhubQuoteRequest(
    val uri: URI,
    val headers: Map<String, String>,
)

internal fun finnhubQuoteRequest(
    symbol: String,
    apiKey: String,
): FinnhubQuoteRequest =
    FinnhubQuoteRequest(
        uri =
            URI.create(
                "https://finnhub.io/api/v1/quote?symbol=" +
                    URLEncoder.encode(symbol, StandardCharsets.UTF_8),
            ),
        headers = mapOf("X-Finnhub-Token" to apiKey),
    )

internal fun parseYahooRegularMarketPrice(payload: String): Double? =
    runCatching {
        val root = Common.gson.fromJson(payload, Map::class.java) ?: return@runCatching null
        val chart = root["chart"] as? Map<*, *> ?: return@runCatching null
        val result = (chart["result"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val meta = result["meta"] as? Map<*, *> ?: return@runCatching null
        (meta["regularMarketPrice"] as? Number)?.toDouble()
    }.getOrNull()
