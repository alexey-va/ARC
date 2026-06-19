package ru.arc.ops

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Lightweight JDK HttpServer exposing authenticated ops endpoints under /ops/.
 */
class OpsHttpServer(
    private val configProvider: () -> OpsHttpConfig = { OpsHttpConfig.current() },
) {
    private var httpServer: HttpServer? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: configProvider().bindPort

    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled) return

        val address = InetSocketAddress(cfg.bindHost, cfg.bindPort)
        val server = HttpServer.create(address, 0)
        server.createContext("/ops") { exchange -> handle(exchange) }
        server.executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "arc-ops-http").apply { isDaemon = true }
        }
        server.start()
        httpServer = server
        info(
            "Ops HTTP API listening on {}:{} (console={})",
            cfg.bindHost,
            actualPort,
            cfg.consoleEnabled,
        )
        if (cfg.token.isBlank() || cfg.token.startsWith("CHANGE_ME")) {
            warn("Ops HTTP token is not configured — requests will be rejected until token is set in modules/ops-http.yml")
        }
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
    }

    internal fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                respond(exchange, 204, "")
                return
            }

            val cfg = configProvider()
            val headers = exchange.requestHeaders.mapValues { it.value.firstOrNull().orEmpty() }

            if (!OpsAuth.isAuthorized(headers, cfg.token)) {
                val (code, body) = OpsJson.error(401, "Unauthorized")
                respond(exchange, code, body)
                return
            }

            route(exchange, cfg)
        } catch (t: Throwable) {
            error("Ops HTTP handler failed", t)
            val (code, body) = OpsJson.error(500, t.message ?: "Internal error")
            respond(exchange, code, body)
        } finally {
            exchange.close()
        }
    }

    private fun route(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path.removePrefix("/ops").trim('/')
        val segments = if (path.isEmpty()) emptyList() else path.split('/')
        val query = parseQuery(exchange.requestURI.rawQuery)

        when {
            method == "GET" && segments.isEmpty() ->
                respondOk(exchange, routes(cfg))

            method == "GET" && segments == listOf("health") ->
                respondOk(exchange, OpsHttpHandlers.health())

            method == "GET" && segments == listOf("server") ->
                respondOk(exchange, OpsHttpHandlers.serverInfo())

            method == "GET" && segments == listOf("online") ->
                respondOk(exchange, OpsHttpHandlers.onlinePlayers())

            method == "GET" && segments.size == 2 && segments[0] == "player" && segments[1] == "lookup" ->
                handlePlayerLookup(exchange, query["name"])

            method == "GET" && segments.size == 3 && segments[0] == "player" && segments[2] == "where" ->
                handlePlayerWhere(exchange, segments[1])

            method == "GET" && segments.size == 2 && segments[0] == "player" ->
                handlePlayer(exchange, segments[1])

            method == "GET" && segments == listOf("placeholder") ->
                handlePlaceholder(exchange, query)

            method == "GET" && segments.size == 2 && segments[0] == "feature" ->
                respondOk(exchange, OpsHttpHandlers.feature(segments[1]))

            method == "GET" && segments == listOf("permission", "lp") ->
                handleLpPermission(exchange, query)

            method == "GET" && segments == listOf("permission") ->
                handlePermission(exchange, query)

            method == "GET" && segments == listOf("errors") ->
                respondOk(
                    exchange,
                    OpsHttpHandlers.errors(
                        query["limit"]?.toIntOrNull() ?: 50,
                        query["grep"],
                        query["since"]?.toLongOrNull(),
                    ),
                )

            method == "GET" && segments == listOf("config", "hash") ->
                respondOk(
                    exchange,
                    OpsHttpHandlers.configHash(
                        paths = queryList(query, "path"),
                        prefixes = queryList(query, "prefix"),
                        limit = query["limit"]?.toIntOrNull() ?: 200,
                    ),
                )

            method == "GET" && segments == listOf("modules") ->
                respondOk(exchange, OpsHttpHandlers.modules())

            method == "GET" && segments == listOf("plugins") ->
                respondOk(
                    exchange,
                    OpsHttpHandlers.plugins(
                        query["limit"]?.toIntOrNull() ?: 100,
                        query["status"],
                    ),
                )

            method == "GET" && segments == listOf("redis") ->
                respondOk(exchange, OpsHttpHandlers.redis())

            method == "POST" && segments == listOf("message") ->
                handleMessage(exchange, cfg)

            method == "POST" && segments == listOf("effect") ->
                handleEffect(exchange, cfg)

            method == "POST" && segments == listOf("reload") ->
                handleReload(exchange, cfg)

            method == "POST" && segments == listOf("console") ->
                handleConsole(exchange, cfg)

            method == "POST" && segments == listOf("run-as") ->
                handleRunAs(exchange, cfg)

            else -> {
                val (code, body) = OpsJson.error(404, "Not found", mapOf("path" to exchange.requestURI.path))
                respond(exchange, code, body)
            }
        }
    }

    private fun handlePlayerWhere(
        exchange: HttpExchange,
        rawName: String,
    ) {
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        respondOk(exchange, OpsHttpHandlers.playerWhere(name))
    }

    private fun handlePlaceholder(
        exchange: HttpExchange,
        query: Map<String, String>,
    ) {
        val player = query["player"]
        val text = query["text"]
        if (player.isNullOrBlank() || text.isNullOrBlank()) {
            val (code, body) = OpsJson.error(400, "Query params required: player, text")
            respond(exchange, code, body)
            return
        }
        try {
            respondOk(exchange, OpsHttpHandlers.parsePlaceholder(player, text))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handleRunAs(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.runAsEnabled) {
            val (code, body) = OpsJson.error(403, "Run-as endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val player = body.get("player")?.asString
        val command = body.get("command")?.asString
        if (player.isNullOrBlank() || command.isNullOrBlank()) {
            val (code, json) = OpsJson.error(400, "JSON body required: {\"player\":\"...\",\"command\":\"...\"}")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsHttpHandlers.runAs(player, command))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handlePlayer(
        exchange: HttpExchange,
        rawName: String,
    ) {
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        val data = OpsHttpHandlers.player(name)
        if (data == null) {
            val (code, body) = OpsJson.error(404, "Player not online", mapOf("player" to name))
            respond(exchange, code, body)
        } else {
            respondOk(exchange, data)
        }
    }

    private fun handlePermission(
        exchange: HttpExchange,
        query: Map<String, String>,
    ) {
        val player = query["player"]
        val node = query["node"] ?: query["permission"]
        if (player.isNullOrBlank() || node.isNullOrBlank()) {
            val (code, body) =
                OpsJson.error(
                    400,
                    "Query params required: player, node (or permission)",
                )
            respond(exchange, code, body)
            return
        }
        respondOk(exchange, OpsHttpHandlers.permissionCheck(player, node))
    }

    private fun handlePlayerLookup(
        exchange: HttpExchange,
        nameFromQuery: String?,
    ) {
        val name =
            nameFromQuery?.takeIf { it.isNotBlank() }
                ?: run {
                    val (code, body) = OpsJson.error(400, "Query param required: name")
                    respond(exchange, code, body)
                    return
                }
        respondOk(exchange, OpsHttpHandlers.playerLookup(name))
    }

    private fun handleLpPermission(
        exchange: HttpExchange,
        query: Map<String, String>,
    ) {
        val player = query["player"]
        val node = query["node"] ?: query["permission"]
        if (player.isNullOrBlank() || node.isNullOrBlank()) {
            val (code, body) = OpsJson.error(400, "Query params required: player, node (or permission)")
            respond(exchange, code, body)
            return
        }
        respondOk(exchange, OpsHttpHandlers.lpPermissionCheck(player, node))
    }

    private fun handleMessage(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.messagesEnabled) {
            val (code, body) = OpsJson.error(403, "Messages endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsHttpHandlers.sendMessage(body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handleEffect(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.effectsEnabled) {
            val (code, body) = OpsJson.error(403, "Effects endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsHttpHandlers.applyEffect(body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handleReload(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.reloadEnabled) {
            val (code, body) = OpsJson.error(403, "Reload endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val target = body.get("target")?.asString
        if (target.isNullOrBlank()) {
            val (code, json) = OpsJson.error(400, "JSON body required: {\"target\":\"arc\"|\"denizen\"}")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsHttpHandlers.scopedReload(target))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun parseJsonBody(exchange: HttpExchange): com.google.gson.JsonObject? {
        val bodyText = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return runCatching {
            com.google.gson.JsonParser.parseString(bodyText).asJsonObject
        }.getOrElse {
            val (code, body) = OpsJson.error(400, "Invalid JSON body")
            respond(exchange, code, body)
            null
        }
    }

    private fun queryList(
        query: Map<String, String>,
        key: String,
    ): List<String> {
        val single = query[key] ?: return emptyList()
        return single.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun handleConsole(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.consoleEnabled) {
            val (code, body) = OpsJson.error(403, "Console endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val bodyText = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val command =
            runCatching {
                com.google.gson.JsonParser.parseString(bodyText).asJsonObject.get("command")?.asString
            }.getOrNull()
        if (command.isNullOrBlank()) {
            val (code, body) = OpsJson.error(400, "JSON body required: {\"command\": \"...\"}")
            respond(exchange, code, body)
            return
        }
        respondOk(exchange, OpsHttpHandlers.runConsole(command))
    }

    private fun routes(cfg: OpsHttpConfig): Map<String, Any?> {
        val routes =
            mutableListOf(
                "GET /ops/",
                "GET /ops/health",
                "GET /ops/server",
                "GET /ops/online",
                "GET /ops/player/{name}",
                "GET /ops/player/{name}/where",
                "GET /ops/player/lookup?name=",
                "GET /ops/placeholder?player=&text=",
                "GET /ops/feature/{module}",
                "GET /ops/permission?player=&node=",
                "GET /ops/permission/lp?player=&node=",
                "GET /ops/errors?limit=&grep=&since=",
                "GET /ops/config/hash?path=&prefix=&limit=",
                "GET /ops/modules",
                "GET /ops/plugins?status=ok|disabled",
                "GET /ops/redis",
            )
        if (cfg.messagesEnabled) {
            routes += "POST /ops/message {\"channel\":\"broadcast|player|ops\",\"text\":\"...\"}"
        }
        if (cfg.effectsEnabled) {
            routes += "POST /ops/effect {\"player\":\"\",\"type\":\"title|actionbar|sound\"}"
        }
        if (cfg.reloadEnabled) {
            routes += "POST /ops/reload {\"target\":\"arc\"|\"denizen\"}"
        }
        if (cfg.consoleEnabled) {
            routes += "POST /ops/console {\"command\":\"...\"}"
        }
        if (cfg.runAsEnabled) {
            routes += "POST /ops/run-as {\"player\":\"...\",\"command\":\"...\"}"
        }
        return mapOf("routes" to routes, "auth" to "Bearer token or X-ARC-Ops-Token header")
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
    }

    private fun respondOk(
        exchange: HttpExchange,
        data: Map<String, Any?>,
    ) {
        respond(exchange, 200, OpsJson.ok(data))
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
