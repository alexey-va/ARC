package ru.arc.ops

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.arc.ARC
import ru.arc.ops.luckperms.LpSubjectRef
import ru.arc.ops.luckperms.LpSubjectType
import ru.arc.ops.luckperms.LpWriteGateException
import ru.arc.ops.luckperms.OpsLuckPermsHandlers
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal const val OPS_MAX_REQUEST_BODY_BYTES = 1_048_576

internal class OpsRequestBodyTooLargeException : RuntimeException()

internal fun readOpsRequestBody(
    input: InputStream,
    maxBytes: Int = OPS_MAX_REQUEST_BODY_BYTES,
): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val bytes = input.readNBytes(maxBytes + 1)
    if (bytes.size > maxBytes) {
        throw OpsRequestBodyTooLargeException()
    }
    return String(bytes, StandardCharsets.UTF_8)
}

/**
 * Lightweight JDK HttpServer exposing authenticated ops endpoints under /ops/.
 */
class OpsHttpServer(
    private val executorFactory: () -> ExecutorService = {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "arc-ops-http").apply { isDaemon = true }
        }
    },
    private val configProvider: () -> OpsHttpConfig = { OpsHttpConfig.current() },
) {
    @Volatile
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: configProvider().bindPort

    @Synchronized
    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled) return

        val address = InetSocketAddress(cfg.bindHost, cfg.bindPort)
        val server = HttpServer.create(address, 0)
        server.createContext("/ops") { exchange -> handle(exchange) }
        val newExecutor = executorFactory()
        server.executor = newExecutor
        try {
            server.start()
        } catch (e: Exception) {
            newExecutor.shutdownNow()
            server.stop(0)
            throw e
        }
        executor = newExecutor
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

    @Synchronized
    fun stop() {
        httpServer?.stop(1)
        httpServer = null
        executor?.shutdownNow()
        executor = null
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
        } catch (_: OpsRequestBodyTooLargeException) {
            val (code, body) = OpsJson.error(413, "Request body too large")
            respond(exchange, code, body)
        } catch (t: Throwable) {
            error("Ops HTTP handler failed", t)
            val (code, body) = OpsJson.error(500, "Internal error")
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
        val path = exchange.requestURI.rawPath.removePrefix("/ops").trim('/')
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

            method == "GET" && segments.size == 3 && segments[0] == "player" && segments[2] == "inventory" ->
                handlePlayerInventory(exchange, cfg, segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "player" && segments[2] == "item" ->
                handlePlayerItem(exchange, cfg, segments[1], query)

            method == "POST" && segments.size == 3 && segments[0] == "player" && segments[2] == "give" ->
                handlePlayerGive(exchange, cfg, segments[1])

            method == "POST" && segments.size == 3 && segments[0] == "player" && segments[2] == "give-preset" ->
                handlePlayerGivePreset(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("item", "preview") ->
                handleItemPreview(exchange, cfg)

            method == "GET" && segments == listOf("placeholder") ->
                handlePlaceholder(exchange, query)

            method == "GET" && segments.size == 2 && segments[0] == "feature" ->
                respondOk(exchange, OpsHttpHandlers.feature(segments[1]))

            method == "GET" && segments == listOf("permission", "lp") ->
                handleLpPermission(exchange, query)

            method == "GET" && segments == listOf("permission") ->
                handlePermission(exchange, query)

            method == "GET" && segments == listOf("luckperms", "groups") ->
                handleLuckPermsRead(exchange, cfg.luckpermsGroupsReadEnabled) {
                    OpsLuckPermsHandlers.current().groups()
                }

            method == "GET" && segments.size == 3 && segments[0] == "luckperms" && segments[1] == "groups" ->
                handleLuckPermsRead(exchange, cfg.luckpermsGroupsReadEnabled) {
                    OpsLuckPermsHandlers.current().group(decodeSegment(segments[2]))
                }

            method == "GET" && segments == listOf("luckperms", "users", "lookup") ->
                handleLuckPermsRead(exchange, cfg.luckpermsUsersReadEnabled) {
                    OpsLuckPermsHandlers.current().userLookup(query["name"] ?: throw IllegalArgumentException("Missing name"))
                }

            method == "GET" && segments.size == 3 && segments[0] == "luckperms" && segments[1] == "users" ->
                handleLuckPermsRead(exchange, cfg.luckpermsUsersReadEnabled) {
                    OpsLuckPermsHandlers.current().user(decodeSegment(segments[2]))
                }

            method == "POST" && segments == listOf("luckperms", "check") ->
                handleLuckPermsRead(exchange, cfg.luckpermsUsersReadEnabled) {
                    OpsLuckPermsHandlers.current().check(readRequestBody(exchange))
                }

            method == "POST" &&
                segments.size == 5 &&
                segments[0] == "luckperms" &&
                segments[1] == "subjects" &&
                segments[4] == "preview" ->
                handleLuckPermsWrite(exchange, cfg, parseLuckPermsSubject(segments[2], segments[3]), preview = true)

            method == "POST" &&
                segments.size == 5 &&
                segments[0] == "luckperms" &&
                segments[1] == "subjects" &&
                segments[4] == "apply" ->
                handleLuckPermsWrite(exchange, cfg, parseLuckPermsSubject(segments[2], segments[3]), preview = false)

            method == "POST" && segments == listOf("luckperms", "reconcile", "preview") ->
                handleLuckPermsMigrationPreview(exchange, cfg)

            method == "POST" && segments == listOf("luckperms", "reconcile", "apply") ->
                handleLuckPermsMigrationApply(exchange, cfg)

            method == "POST" && segments == listOf("luckperms", "migrations", "preview") ->
                handleLuckPermsMigrationPreview(exchange, cfg)

            method == "POST" && segments == listOf("luckperms", "migrations", "apply") ->
                handleLuckPermsMigrationApply(exchange, cfg)

            method == "GET" && segments.size == 3 && segments[0] == "luckperms" && segments[1] == "migrations" ->
                handleLuckPermsRead(exchange, cfg.luckpermsMigrationsEnabled) {
                    OpsLuckPermsHandlers.current().migrationStatus(decodeSegment(segments[2]))
                }

            method == "POST" &&
                segments.size == 4 &&
                segments[0] == "luckperms" &&
                segments[1] == "migrations" &&
                segments[3] == "rollback" ->
                handleLuckPermsMigrationRollback(exchange, cfg, decodeSegment(segments[2]))

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

            method == "GET" && segments == listOf("content", "health") ->
                handleContentHealth(exchange, cfg)

            method == "POST" && segments == listOf("message") ->
                handleMessage(exchange, cfg)

            method == "POST" && segments == listOf("broadcast") ->
                handleBroadcast(exchange, cfg)

            method == "POST" && segments == listOf("effect") ->
                handleEffect(exchange, cfg)

            method == "GET" && segments == listOf("item-presets") ->
                handleItemPresetsList(exchange, cfg, null)

            method == "GET" && segments.size == 2 && segments[0] == "item-presets" ->
                handleItemPresetsList(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("item-presets", "preview") ->
                handleItemPresetPreview(exchange, cfg)

            method == "PUT" && segments.size == 2 && segments[0] == "item-presets" ->
                handleItemPresetUpsert(exchange, cfg, segments[1])

            method == "DELETE" && segments.size == 2 && segments[0] == "item-presets" ->
                handleItemPresetDelete(exchange, cfg, segments[1])

            method == "GET" && segments == listOf("cmi", "kits") ->
                handleCmiKitsList(exchange, cfg, null)

            method == "GET" && segments.size == 3 && segments[0] == "cmi" && segments[1] == "kits" ->
                handleCmiKitsList(exchange, cfg, segments[2])

            method == "POST" && segments == listOf("cmi", "kits", "preview") ->
                handleCmiKitPreview(exchange, cfg)

            method == "PUT" && segments.size == 3 && segments[0] == "cmi" && segments[1] == "kits" ->
                handleCmiKitUpsert(exchange, cfg, segments[2])

            method == "GET" && segments == listOf("cmi", "holograms") ->
                handleCmiHologramsList(exchange, cfg, null, query)

            method == "GET" && segments.size == 3 && segments[0] == "cmi" && segments[1] == "holograms" ->
                handleCmiHologramsList(exchange, cfg, segments[2], query)

            method == "POST" && segments == listOf("cmi", "holograms", "preview") ->
                handleCmiHologramPreview(exchange, cfg)

            method == "PUT" && segments.size == 3 && segments[0] == "cmi" && segments[1] == "holograms" ->
                handleCmiHologramUpsert(exchange, cfg, segments[2])

            method == "DELETE" && segments.size == 3 && segments[0] == "cmi" && segments[1] == "holograms" ->
                handleCmiHologramDelete(exchange, cfg, segments[2])

            method == "GET" && segments == listOf("scheduled-commands") ->
                handleScheduledCommandsList(exchange, cfg, null)

            method == "GET" && segments.size == 2 && segments[0] == "scheduled-commands" ->
                handleScheduledCommandsList(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("scheduled-commands", "preview") ->
                handleScheduledCommandPreview(exchange, cfg)

            method == "PUT" && segments.size == 2 && segments[0] == "scheduled-commands" ->
                handleScheduledCommandUpsert(exchange, cfg, segments[1])

            method == "DELETE" && segments.size == 2 && segments[0] == "scheduled-commands" ->
                handleScheduledCommandDelete(exchange, cfg, segments[1])

            method == "GET" && segments == listOf("location-pools") ->
                handleLocationPoolsList(exchange, cfg, null)

            method == "GET" && segments.size == 2 && segments[0] == "location-pools" ->
                handleLocationPoolsList(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("location-pools", "preview") ->
                handleLocationPoolPreview(exchange, cfg)

            method == "PUT" && segments.size == 2 && segments[0] == "location-pools" ->
                handleLocationPoolUpsert(exchange, cfg, segments[1])

            method == "DELETE" && segments.size == 2 && segments[0] == "location-pools" ->
                handleLocationPoolDelete(exchange, cfg, segments[1])

            method == "GET" && segments == listOf("treasure-pools") ->
                handleTreasurePoolsList(exchange, cfg, null)

            method == "GET" && segments.size == 2 && segments[0] == "treasure-pools" ->
                handleTreasurePoolsList(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("treasure-pools", "preview") ->
                handleTreasurePoolPreview(exchange, cfg)

            method == "PUT" && segments.size == 2 && segments[0] == "treasure-pools" ->
                handleTreasurePoolUpsert(exchange, cfg, segments[1])

            method == "DELETE" && segments.size == 2 && segments[0] == "treasure-pools" ->
                handleTreasurePoolDelete(exchange, cfg, segments[1])

            method == "GET" && segments == listOf("npcs") ->
                handleNpcsList(exchange, cfg, query)

            method == "GET" && segments.size == 2 && segments[0] == "npcs" ->
                handleNpcGet(exchange, cfg, segments[1])

            method == "POST" && segments == listOf("npcs", "preview") ->
                handleNpcPreview(exchange, cfg)

            method == "PUT" && segments == listOf("npcs") ->
                handleNpcUpsert(exchange, cfg)

            method == "PUT" && segments.size == 2 && segments[0] == "npcs" ->
                handleNpcUpsert(exchange, cfg, segments[1])

            method == "DELETE" && segments.size == 2 && segments[0] == "npcs" ->
                handleNpcDelete(exchange, cfg, segments[1])

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

    private fun handlePlayerInventory(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.itemsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Item read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        try {
            respondOk(exchange, OpsItemHandlers.playerInventory(name))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handlePlayerItem(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
        query: Map<String, String>,
    ) {
        if (!cfg.itemsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Item read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        val hand = query["hand"]?.equals("true", ignoreCase = true) == true
        val slot = query["slot"]?.toIntOrNull()
        try {
            respondOk(exchange, OpsItemHandlers.playerItem(name, slot, hand))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handlePlayerGive(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.itemsGiveEnabled) {
            val (code, body) = OpsJson.error(403, "Item give endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsItemHandlers.giveItem(name, body, cfg.itemsGiveMaxStack))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        }
    }

    private fun handlePlayerGivePreset(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.itemsGiveEnabled) {
            val (code, body) = OpsJson.error(403, "Item give endpoint disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsItemPresetHandlers.give(name, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Item presets unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleItemPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.itemsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Item preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsItemHandlers.previewItem(body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
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

    private fun handleContentHealth(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        try {
            respondOk(exchange, OpsContentHealthHandlers.health(cfg))
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Content health unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleBroadcast(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.messagesEnabled) {
            val (code, body) = OpsJson.error(403, "Broadcast endpoint disabled (messages-enabled=false)")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsHttpHandlers.publishBroadcast(body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "XAction unavailable")
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

    private fun handleItemPresetsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String?,
    ) {
        if (!cfg.itemPresetsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Item preset read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = rawId?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            respondOk(exchange, OpsItemPresetHandlers.list(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Item preset not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Item presets unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleItemPresetPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.itemPresetsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Item preset preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val idElement = body.get("id")?.takeIf { !it.isJsonNull }
        val id =
            idElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
        if (id.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires item preset id string")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsItemPresetHandlers.preview(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Item presets unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleItemPresetUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.itemPresetsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Item preset writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsItemPresetHandlers.upsert(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Item presets unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleItemPresetDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.itemPresetsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Item preset writes disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsItemPresetHandlers.delete(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Item preset not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Item presets unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleCmiKitsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String?,
    ) {
        if (!cfg.itemsReadEnabled) {
            val (code, body) = OpsJson.error(403, "CMI kit read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = rawName?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        try {
            respondOk(exchange, OpsCmiKitHandlers.listKits(name))
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "CMI kit not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "CMI unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleCmiKitPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.itemsReadEnabled) {
            val (code, body) = OpsJson.error(403, "CMI kit preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val name = body.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
        if (name.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires kit name")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsCmiKitHandlers.preview(name, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "CMI unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleCmiKitUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.cmiKitsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "CMI kit writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        val body = parseJsonBody(exchange) ?: return
        try {
            respondOk(exchange, OpsCmiKitHandlers.upsert(name, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "CMI unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleCmiHologramsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String?,
        query: Map<String, String>,
    ) {
        if (!cfg.cmiHologramsReadEnabled) {
            val (code, body) = OpsJson.error(403, "CMI hologram read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        handleCmiHologramErrors(exchange) {
            OpsCmiHologramHandlers.list(
                name = rawName?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) },
                worldName = query["world"],
                limit = query["limit"]?.toIntOrNull() ?: 200,
            )
        }
    }

    private fun handleCmiHologramPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.cmiHologramsReadEnabled) {
            val (code, body) = OpsJson.error(403, "CMI hologram preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val name = body.remove("name")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
        if (name.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires hologram name string")
            respond(exchange, code, json)
            return
        }
        handleCmiHologramErrors(exchange) {
            OpsCmiHologramHandlers.preview(name, body)
        }
    }

    private fun handleCmiHologramUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.cmiHologramsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "CMI hologram writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        handleCmiHologramErrors(exchange) {
            OpsCmiHologramHandlers.upsert(
                URLDecoder.decode(rawName, StandardCharsets.UTF_8),
                body,
            )
        }
    }

    private fun handleCmiHologramDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawName: String,
    ) {
        if (!cfg.cmiHologramsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "CMI hologram writes disabled in config")
            respond(exchange, code, body)
            return
        }
        handleCmiHologramErrors(exchange) {
            OpsCmiHologramHandlers.delete(URLDecoder.decode(rawName, StandardCharsets.UTF_8))
        }
    }

    private fun handleCmiHologramErrors(
        exchange: HttpExchange,
        block: () -> Map<String, Any?>,
    ) {
        try {
            respondOk(exchange, block())
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "CMI hologram not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "CMI unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleScheduledCommandsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String?,
    ) {
        if (!cfg.scheduledCommandsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Scheduled command read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = rawId?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            respondOk(exchange, OpsScheduledCommandHandlers.list(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Scheduled command not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Scheduled commands unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleScheduledCommandPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.scheduledCommandsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Scheduled command preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val idElement = body.get("id")?.takeIf { !it.isJsonNull }
        val id =
            idElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
        if (id.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires scheduled command id string")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsScheduledCommandHandlers.preview(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Scheduled commands unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleScheduledCommandUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.scheduledCommandsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Scheduled command writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsScheduledCommandHandlers.upsert(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Scheduled commands unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleScheduledCommandDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.scheduledCommandsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Scheduled command writes disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsScheduledCommandHandlers.delete(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Scheduled command not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Scheduled commands unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleLocationPoolsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String?,
    ) {
        if (!cfg.locationPoolsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Location pool read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = rawId?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            respondOk(exchange, OpsLocationPoolHandlers.list(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Location pool not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Location pools unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleLocationPoolPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.locationPoolsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Location pool preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val idElement = body.get("id")?.takeIf { !it.isJsonNull }
        val id =
            idElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
        if (id.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires location pool id string")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsLocationPoolHandlers.preview(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Location pools unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleLocationPoolUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.locationPoolsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Location pool writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsLocationPoolHandlers.upsert(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Location pools unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleLocationPoolDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.locationPoolsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Location pool writes disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsLocationPoolHandlers.delete(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Location pool not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Location pools unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleTreasurePoolsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String?,
    ) {
        if (!cfg.treasurePoolsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Treasure pool read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = rawId?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            respondOk(exchange, OpsTreasurePoolHandlers.list(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Treasure pool not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Treasure pools unavailable")
            respond(exchange, code, body)
        }
    }

    private fun handleTreasurePoolPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.treasurePoolsReadEnabled) {
            val (code, body) = OpsJson.error(403, "Treasure pool preview disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        val idElement = body.get("id")?.takeIf { !it.isJsonNull }
        val id =
            idElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
        if (id.isNullOrEmpty()) {
            val (code, json) = OpsJson.error(400, "JSON body requires treasure pool id string")
            respond(exchange, code, json)
            return
        }
        try {
            respondOk(exchange, OpsTreasurePoolHandlers.preview(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Treasure pools unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleTreasurePoolUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.treasurePoolsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Treasure pool writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsTreasurePoolHandlers.upsert(id, body))
        } catch (e: IllegalArgumentException) {
            val (code, json) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, json)
        } catch (e: IllegalStateException) {
            val (code, json) = OpsJson.error(503, e.message ?: "Treasure pools unavailable")
            respond(exchange, code, json)
        }
    }

    private fun handleTreasurePoolDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.treasurePoolsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "Treasure pool writes disabled in config")
            respond(exchange, code, body)
            return
        }
        try {
            val id = URLDecoder.decode(rawId, StandardCharsets.UTF_8)
            respondOk(exchange, OpsTreasurePoolHandlers.delete(id))
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "Treasure pool not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "Treasure pools unavailable")
            respond(exchange, code, body)
        }
    }

    private fun parseJsonBody(exchange: HttpExchange): com.google.gson.JsonObject? {
        val bodyText = readRequestBody(exchange)
        return runCatching {
            com.google.gson.JsonParser.parseString(bodyText).asJsonObject
        }.getOrElse {
            val (code, body) = OpsJson.error(400, "Invalid JSON body")
            respond(exchange, code, body)
            null
        }
    }

    private fun handleNpcsList(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        query: Map<String, String>,
    ) {
        if (!cfg.npcsReadEnabled) {
            val (code, body) = OpsJson.error(403, "NPC read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        handleNpcErrors(exchange) {
            OpsNpcHandlers.list(
                worldName = query["world"],
                limit = query["limit"]?.toIntOrNull() ?: 200,
            )
        }
    }

    private fun handleNpcGet(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.npcsReadEnabled) {
            val (code, body) = OpsJson.error(403, "NPC read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        handleNpcErrors(exchange) {
            OpsNpcHandlers.list(id = parseNpcId(rawId))
        }
    }

    private fun handleNpcPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.npcsReadEnabled) {
            val (code, body) = OpsJson.error(403, "NPC read endpoints disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        handleNpcErrors(exchange) { OpsNpcHandlers.preview(body) }
    }

    private fun handleNpcUpsert(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String? = null,
    ) {
        if (!cfg.npcsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "NPC writes disabled in config")
            respond(exchange, code, body)
            return
        }
        val body = parseJsonBody(exchange) ?: return
        handleNpcErrors(exchange) { OpsNpcHandlers.upsert(rawId?.let(::parseNpcId), body) }
    }

    private fun handleNpcDelete(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        rawId: String,
    ) {
        if (!cfg.npcsWriteEnabled) {
            val (code, body) = OpsJson.error(403, "NPC writes disabled in config")
            respond(exchange, code, body)
            return
        }
        handleNpcErrors(exchange) { OpsNpcHandlers.delete(parseNpcId(rawId)) }
    }

    private fun parseNpcId(rawId: String): Int =
        URLDecoder.decode(rawId, StandardCharsets.UTF_8).toIntOrNull()
            ?: throw IllegalArgumentException("NPC id must be an integer")

    private fun handleNpcErrors(
        exchange: HttpExchange,
        block: () -> Map<String, Any?>,
    ) {
        try {
            respondOk(exchange, block())
        } catch (e: IllegalArgumentException) {
            val (code, body) = OpsJson.error(400, e.message ?: "Bad request")
            respond(exchange, code, body)
        } catch (e: NoSuchElementException) {
            val (code, body) = OpsJson.error(404, e.message ?: "NPC not found")
            respond(exchange, code, body)
        } catch (e: IllegalStateException) {
            val (code, body) = OpsJson.error(503, e.message ?: "NPC provider unavailable")
            respond(exchange, code, body)
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
        val bodyText = readRequestBody(exchange)
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

    private fun readRequestBody(exchange: HttpExchange): String =
        readOpsRequestBody(exchange.requestBody)

    private fun handleLuckPermsRead(
        exchange: HttpExchange,
        enabled: Boolean,
        block: () -> Map<String, Any?>,
    ) {
        if (!enabled) {
            respondError(exchange, 403, "LuckPerms read endpoint disabled in config")
            return
        }
        handleLuckPermsErrors(exchange, block)
    }

    private fun handleLuckPermsWrite(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        ref: LpSubjectRef,
        preview: Boolean,
    ) {
        val enabled =
            when (ref.type) {
                LpSubjectType.GROUP -> cfg.luckpermsGroupsWriteEnabled
                LpSubjectType.USER -> cfg.luckpermsUsersWriteEnabled
            }
        if (!enabled || ARC.serverName != "spawn") {
            respondError(exchange, 403, "LuckPerms writes are enabled only on spawn")
            return
        }
        handleLuckPermsErrors(exchange) {
            val body = readRequestBody(exchange)
            if (preview) OpsLuckPermsHandlers.current().preview(ref, body) else OpsLuckPermsHandlers.current().apply(ref, body)
        }
    }

    private fun handleLuckPermsMigrationPreview(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.luckpermsMigrationsEnabled || ARC.serverName != "spawn") {
            respondError(exchange, 403, "LuckPerms migrations are enabled only on spawn")
            return
        }
        handleLuckPermsErrors(exchange) {
            OpsLuckPermsHandlers.current().migrationPreview(readRequestBody(exchange))
        }
    }

    private fun handleLuckPermsMigrationApply(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
    ) {
        if (!cfg.luckpermsMigrationsEnabled || ARC.serverName != "spawn") {
            respondError(exchange, 403, "LuckPerms migrations are enabled only on spawn")
            return
        }
        handleLuckPermsErrors(exchange) {
            val root = com.google.gson.JsonParser.parseString(readRequestBody(exchange)).asJsonObject
            require(root.keySet() == setOf("version", "jobId", "idempotencyKey")) {
                "Unknown migration apply fields"
            }
            require(root.get("version")?.asInt == 1) { "Unsupported LuckPerms request version" }
            val jobId = root.get("jobId")?.asString ?: throw IllegalArgumentException("Missing jobId")
            val key = root.get("idempotencyKey")?.asString ?: throw IllegalArgumentException("Missing idempotencyKey")
            OpsLuckPermsHandlers.current().migrationApply(
                jobId,
                com.google.gson.Gson().toJson(mapOf("version" to 1, "idempotencyKey" to key)),
            )
        }
    }

    private fun handleLuckPermsMigrationRollback(
        exchange: HttpExchange,
        cfg: OpsHttpConfig,
        jobId: String,
    ) {
        if (!cfg.luckpermsMigrationsEnabled || ARC.serverName != "spawn") {
            respondError(exchange, 403, "LuckPerms migrations are enabled only on spawn")
            return
        }
        handleLuckPermsErrors(exchange) {
            OpsLuckPermsHandlers.current().migrationRollback(jobId, readRequestBody(exchange))
        }
    }

    private fun handleLuckPermsErrors(
        exchange: HttpExchange,
        block: () -> Map<String, Any?>,
    ) {
        try {
            respondOk(exchange, block())
        } catch (e: NoSuchElementException) {
            respondError(exchange, 404, e.message ?: "LuckPerms subject not found")
        } catch (e: LpWriteGateException) {
            respondError(exchange, 403, e.message ?: "LuckPerms write disabled")
        } catch (e: IllegalArgumentException) {
            respondError(exchange, 422, e.message ?: "Invalid LuckPerms request")
        } catch (e: IllegalStateException) {
            respondError(exchange, 409, e.message ?: "LuckPerms state conflict")
        } catch (e: java.util.concurrent.CompletionException) {
            val cause = e.cause ?: e
            when (cause) {
                is NoSuchElementException -> respondError(exchange, 404, cause.message ?: "LuckPerms subject not found")
                is LpWriteGateException -> respondError(exchange, 403, cause.message ?: "LuckPerms write disabled")
                is IllegalArgumentException -> respondError(exchange, 422, cause.message ?: "Invalid LuckPerms request")
                is IllegalStateException -> respondError(exchange, 409, cause.message ?: "LuckPerms state conflict")
                else -> throw e
            }
        }
    }

    private fun parseLuckPermsSubject(
        type: String,
        rawIdentifier: String,
    ): LpSubjectRef =
        LpSubjectRef(
            when (type) {
                "group" -> LpSubjectType.GROUP
                "user" -> LpSubjectType.USER
                else -> throw IllegalArgumentException("Unsupported LuckPerms subject type: $type")
            },
            decodeSegment(rawIdentifier),
        )

    private fun decodeSegment(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun respondError(
        exchange: HttpExchange,
        status: Int,
        message: String,
    ) {
        val (code, body) = OpsJson.error(status, message)
        respond(exchange, code, body)
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
                "GET /ops/content/health",
            )
        if (cfg.messagesEnabled) {
            routes += "POST /ops/message {\"channel\":\"broadcast|player|ops\",\"text\":\"...\"}"
            routes +=
                "POST /ops/broadcast {\"text\":\"...\",\"type\":\"chat|boss_bar|action_bar\",\"servers\":\"spawn,survival|all\",\"permission\":\"...\"}"
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
        if (cfg.itemsReadEnabled) {
            routes += "GET /ops/player/{name}/inventory"
            routes += "GET /ops/player/{name}/item?slot= | ?hand=true"
            routes += "POST /ops/item/preview {ItemSpec JSON}"
            routes += "GET /ops/cmi/kits[/{name}]"
            routes += "POST /ops/cmi/kits/preview {name,display,icon:ItemSpec,items:{},commands:[]}"
        }
        if (cfg.itemsGiveEnabled) {
            routes += "POST /ops/player/{name}/give {\"item\":{ItemSpec},\"slot\":-1,\"dropOverflow\":true}"
            routes += "POST /ops/player/{name}/give-preset {\"preset\":\"sf_lootbox\",\"amount\":1,\"dropOverflow\":true}"
        }
        if (cfg.itemPresetsReadEnabled) {
            routes += "GET /ops/item-presets[/{id}]"
            routes += "POST /ops/item-presets/preview {id,type:preset|bundle,description,item|items}"
        }
        if (cfg.itemPresetsWriteEnabled) {
            routes += "PUT /ops/item-presets/{id} {type:preset|bundle,description,item|items}"
            routes += "DELETE /ops/item-presets/{id}"
        }
        if (cfg.cmiKitsWriteEnabled) {
            routes += "PUT /ops/cmi/kits/{name} {display,icon:ItemSpec,items:{},extraItems:{},commands:[]}"
        }
        if (cfg.cmiHologramsReadEnabled) {
            routes += "GET /ops/cmi/holograms[/{name}]?world=&limit="
            routes += "POST /ops/cmi/holograms/preview {name,HologramSpec}"
        }
        if (cfg.cmiHologramsWriteEnabled) {
            routes += "PUT /ops/cmi/holograms/{name} HologramSpec"
            routes += "DELETE /ops/cmi/holograms/{name}"
        }
        if (cfg.scheduledCommandsReadEnabled) {
            routes += "GET /ops/scheduled-commands[/{id}]"
            routes += "POST /ops/scheduled-commands/preview {id,command,servers,schedule:{...}}"
        }
        if (cfg.scheduledCommandsWriteEnabled) {
            routes += "PUT /ops/scheduled-commands/{id} {command,servers,schedule:{...}}"
            routes += "DELETE /ops/scheduled-commands/{id}"
        }
        if (cfg.locationPoolsReadEnabled) {
            routes += "GET /ops/location-pools[/{id}]"
            routes += "POST /ops/location-pools/preview {id,locations:[{server,world,x,y,z,yaw,pitch,weight}]}"
        }
        if (cfg.locationPoolsWriteEnabled) {
            routes += "PUT /ops/location-pools/{id} {locations:[{server,world,x,y,z,yaw,pitch,weight}]}"
            routes += "DELETE /ops/location-pools/{id}"
        }
        if (cfg.treasurePoolsReadEnabled) {
            routes += "GET /ops/treasure-pools[/{id}]"
            routes += "POST /ops/treasure-pools/preview {id,messages:[],treasures:[{type,weight,...}]}"
        }
        if (cfg.treasurePoolsWriteEnabled) {
            routes += "PUT /ops/treasure-pools/{id} {messages:[],treasures:[{type,weight,...}]}"
            routes += "DELETE /ops/treasure-pools/{id}"
        }
        if (cfg.npcsReadEnabled) {
            routes += "GET /ops/npcs[/{id}]?world=&limit="
            routes += "POST /ops/npcs/preview {world,x,y?,z,yaw?,pitch?}"
        }
        if (cfg.npcsWriteEnabled) {
            routes += "PUT /ops/npcs[/{id}] NpcSpec (create without id; patch existing with id)"
            routes += "DELETE /ops/npcs/{id}"
        }
        if (cfg.luckpermsGroupsReadEnabled) {
            routes += "GET /ops/luckperms/groups"
            routes += "GET /ops/luckperms/groups/{name}"
        }
        if (cfg.luckpermsUsersReadEnabled) {
            routes += "GET /ops/luckperms/users/{uuid}"
            routes += "GET /ops/luckperms/users/lookup?name="
            routes += "POST /ops/luckperms/check"
        }
        if (cfg.luckpermsGroupsWriteEnabled || cfg.luckpermsUsersWriteEnabled) {
            routes += "POST /ops/luckperms/subjects/{group|user}/{id}/preview"
            routes += "POST /ops/luckperms/subjects/{group|user}/{id}/apply"
        }
        if (cfg.luckpermsMigrationsEnabled) {
            routes += "POST /ops/luckperms/reconcile/preview"
            routes += "POST /ops/luckperms/reconcile/apply"
            routes += "POST /ops/luckperms/migrations/preview"
            routes += "POST /ops/luckperms/migrations/apply"
            routes += "GET /ops/luckperms/migrations/{jobId}"
            routes += "POST /ops/luckperms/migrations/{jobId}/rollback"
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
