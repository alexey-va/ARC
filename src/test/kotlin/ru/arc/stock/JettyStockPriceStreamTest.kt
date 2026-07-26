package ru.arc.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jetty.websocket.api.RemoteEndpoint
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.WebSocketClient
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JettyStockPriceStreamTest : FreeSpec({
    "subscribes distinct symbols, forwards batched prices and closes once" {
        val client = mockk<WebSocketClient>()
        val session = mockk<Session>()
        val remote = mockk<RemoteEndpoint>(relaxed = true)
        val socket = slot<Any>()
        val connectFuture = mockk<Future<Session>>(relaxed = true)
        every { client.start() } returns Unit
        every { client.isRunning } returns true
        every { client.stop() } returns Unit
        every { session.remote } returns remote
        every { client.connect(capture(socket), any<URI>()) } answers {
            (socket.captured as WebSocketAdapter).onWebSocketConnect(session)
            connectFuture
        }
        val prices = CopyOnWriteArrayList<Pair<String, Double>>()
        val stream =
            JettyStockPriceStream(
                apiKey = "key",
                onPrice = { symbol, price -> prices += symbol to price },
                client = client,
                executor = Executors.newSingleThreadExecutor(),
                connectionTimeoutSeconds = 1,
                subscriptionDelayMs = 0,
            )

        stream.start(listOf("AAPL", "AAPL", "MSFT"))

        verify(timeout = 1_000, exactly = 1) {
            remote.sendString("""{"type":"subscribe","symbol":"AAPL"}""")
        }
        verify(timeout = 1_000, exactly = 1) {
            remote.sendString("""{"type":"subscribe","symbol":"MSFT"}""")
        }
        (socket.captured as WebSocketAdapter).onWebSocketText(
            """{"data":[{"s":"AAPL","p":10.5},{"s":"MSFT","p":20}]}""",
        )
        prices shouldContainExactly listOf("AAPL" to 10.5, "MSFT" to 20.0)

        stream.close()
        stream.close()

        verify(exactly = 1) { client.stop() }
    }
})
