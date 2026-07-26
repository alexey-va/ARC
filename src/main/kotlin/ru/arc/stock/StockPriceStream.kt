package ru.arc.stock

import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.WebSocketClient
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface StockPriceStream : AutoCloseable {
    val isRunning: Boolean

    fun start(symbols: Collection<String>)
}

internal fun interface StockPriceStreamFactory {
    fun create(
        apiKey: String,
        onPrice: (String, Double) -> Unit,
    ): StockPriceStream
}

internal class JettyStockPriceStream(
    apiKey: String,
    private val onPrice: (String, Double) -> Unit,
    private val client: WebSocketClient = WebSocketClient(),
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "arc-stock-subscriptions").apply { isDaemon = true }
        },
    private val connectionTimeoutSeconds: Long = 5L,
    private val subscriptionDelayMs: Long = 100L,
) : StockPriceStream {
    private val uri = URI("wss://ws.finnhub.io?token=$apiKey")
    private val socket = FinnhubSocket()
    private val started = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    private var subscriptionTask: Future<*>? = null

    override val isRunning: Boolean
        get() = active.get() && client.isRunning

    override fun start(symbols: Collection<String>) {
        check(started.compareAndSet(false, true)) { "Stock price stream already started" }
        try {
            client.start()
            active.set(true)
            client.connect(socket, uri)
            subscriptionTask =
                executor.submit {
                    subscribe(symbols.distinct())
                }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun subscribe(symbols: Collection<String>) {
        try {
            if (!socket.connected.await(connectionTimeoutSeconds, TimeUnit.SECONDS)) {
                warn("Stock WebSocket connection was not established within {} seconds", connectionTimeoutSeconds)
                close()
                return
            }
            for (symbol in symbols) {
                if (!active.get()) return
                socket.session.remote.sendString("""{"type":"subscribe","symbol":"$symbol"}""")
                if (subscriptionDelayMs > 0) Thread.sleep(subscriptionDelayMs)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            error("Could not subscribe to stock WebSocket", e)
            close()
        }
    }

    override fun close() {
        active.set(false)
        if (cleaned.compareAndSet(false, true)) {
            subscriptionTask?.cancel(true)
            subscriptionTask = null
            runCatching {
                if (client.isRunning || client.isStarted || client.isStarting) {
                    client.stop()
                }
            }.onFailure { error("Could not stop stock WebSocket client", it) }
            executor.shutdownNow()
        }
    }

    private fun signalClosed() {
        active.set(false)
        executor.shutdownNow()
    }

    private inner class FinnhubSocket : WebSocketAdapter() {
        val connected = CountDownLatch(1)

        override fun onWebSocketConnect(session: Session) {
            super.onWebSocketConnect(session)
            connected.countDown()
        }

        override fun onWebSocketText(message: String) {
            super.onWebSocketText(message)
            FinnhubMessageParser.parse(message).forEach { (symbol, price) ->
                onPrice(symbol, price)
            }
        }

        override fun onWebSocketClose(statusCode: Int, reason: String) {
            super.onWebSocketClose(statusCode, reason)
            info("Stock WebSocket closed: {} - {}", statusCode, reason)
            signalClosed()
        }

        override fun onWebSocketError(cause: Throwable) {
            super.onWebSocketError(cause)
            error("Stock WebSocket failed", cause)
            signalClosed()
        }
    }
}

internal object FinnhubMessageParser {
    private val gson = Common.gson

    fun parse(message: String): List<Pair<String, Double>> {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val payload =
                gson.fromJson(message, Map::class.java) as? Map<String, Any>
                    ?: return@runCatching emptyList()
            val rows = payload["data"] as? List<*> ?: return@runCatching emptyList()
            rows.mapNotNull { row ->
                val values = row as? Map<*, *> ?: return@mapNotNull null
                val symbol = values["s"] as? String ?: return@mapNotNull null
                val price = (values["p"] as? Number)?.toDouble() ?: return@mapNotNull null
                symbol to price
            }
        }.getOrDefault(emptyList())
    }
}
