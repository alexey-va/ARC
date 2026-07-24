package ru.arc.metrics

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Localhost-only HTTP server exposing Micrometer Prometheus scrape format.
 */
class MetricsHttpServer(
    private val registry: PrometheusMeterRegistry,
    private val configProvider: () -> MetricsConfig = { MetricsConfig.current() },
) {
    private var httpServer: HttpServer? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: configProvider().bindPort

    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled) return

        val server = HttpServer.create(InetSocketAddress(cfg.bindHost, cfg.bindPort), 0)
        server.createContext("/metrics") { exchange -> handleMetrics(exchange) }
        server.createContext("/health") { exchange -> respond(exchange, 200, "ok\n") }
        server.executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "arc-metrics-http").apply { isDaemon = true }
        }
        server.start()
        httpServer = server
        info("Prometheus metrics on {}:{}", cfg.bindHost, actualPort)
    }

    fun stop() {
        httpServer?.stop(1)
        httpServer = null
    }

    private fun handleMetrics(exchange: HttpExchange) {
        try {
            if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                respond(exchange, 405, "Method Not Allowed\n")
                return
            }
            val body = registry.scrape()
            exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            respond(exchange, 200, body)
        } catch (t: Throwable) {
            error("Metrics scrape failed", t)
            respond(exchange, 500, "error\n")
        } finally {
            exchange.close()
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
