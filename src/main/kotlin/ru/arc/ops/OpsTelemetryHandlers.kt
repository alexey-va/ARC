package ru.arc.ops

import com.sun.net.httpserver.HttpExchange
import ru.arc.metrics.telemetry.PlayerTelemetryModule
import ru.arc.telemetry.PlayerTelemetryCursor
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetryQuery
import ru.arc.telemetry.PlayerTelemetryStore
import ru.arc.telemetry.PlayerTelemetrySummaryQuery
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Authenticated, bounded player-telemetry queries. Every SQL wait stays on an ops worker thread. */
internal fun OpsHttpServer.handlePlayerTelemetry(
    exchange: HttpExchange,
    cfg: OpsHttpConfig,
    method: String,
    path: List<String>,
) {
    if (!cfg.playerTelemetryReadEnabled) {
        respondTelemetryError(exchange, 403, "Player-telemetry read endpoint disabled in config")
        return
    }
    if (method != "GET") {
        respondTelemetryError(exchange, 405, "Player-telemetry endpoints support GET only")
        return
    }

    try {
        when (path) {
            listOf("health") -> {
                OpsTelemetryHandlers.requireNoQuery(exchange.requestURI.rawQuery)
                respondTelemetryOk(exchange, PlayerTelemetryModule.health())
            }
            listOf("catalog") -> {
                OpsTelemetryHandlers.requireNoQuery(exchange.requestURI.rawQuery)
                respondTelemetryOk(exchange, OpsTelemetryHandlers.catalog())
            }
            listOf("events") -> {
                val query = OpsTelemetryHandlers.eventsQuery(exchange.requestURI.rawQuery)
                val store = telemetryStore()
                val page = OpsTelemetryHandlers.await(store, store.query(query))
                respondTelemetryOk(exchange, OpsTelemetryHandlers.page(page))
            }
            listOf("summary") -> {
                val query = OpsTelemetryHandlers.summaryQuery(exchange.requestURI.rawQuery)
                val store = telemetryStore()
                val summary = OpsTelemetryHandlers.await(store, store.summary(query))
                respondTelemetryOk(exchange, OpsTelemetryHandlers.summary(summary))
            }
            else -> respondTelemetryError(exchange, 404, "Unknown player-telemetry endpoint")
        }
    } catch (failure: IllegalArgumentException) {
        respondTelemetryError(exchange, 400, failure.message ?: "Invalid player-telemetry request")
    } catch (failure: TimeoutException) {
        respondTelemetryError(exchange, 504, "Player-telemetry query timed out")
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        respondTelemetryError(exchange, 503, "Player-telemetry query was interrupted")
    } catch (failure: TelemetryUnavailableException) {
        respondTelemetryError(exchange, 503, "Player-telemetry storage is unavailable")
    } catch (failure: Exception) {
        respondTelemetryError(exchange, 503, "Player-telemetry query failed")
    }
}

private fun telemetryStore(): PlayerTelemetryStore =
    PlayerTelemetryModule.storeOrNull() ?: throw TelemetryUnavailableException()

private fun respondTelemetryOk(exchange: HttpExchange, body: Map<String, Any?>) {
    respondTelemetry(exchange, 200, OpsJson.ok(body))
}

private fun respondTelemetryError(exchange: HttpExchange, status: Int, message: String) {
    val (_, body) = OpsJson.error(status, message)
    respondTelemetry(exchange, status, body)
}

private fun respondTelemetry(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private class TelemetryUnavailableException(cause: Throwable? = null) : RuntimeException(cause)

internal object OpsTelemetryHandlers {
    private const val QUERY_TIMEOUT_SECONDS = 4L
    private const val MAX_LIMIT = 500
    private val eventQueryFields = setOf(
        "from", "until", "player-id", "player-name", "session-id", "server", "source", "event", "subject",
        "include-qa", "limit", "cursor-at", "cursor-event-id",
    )
    private val summaryQueryFields = eventQueryFields - setOf("cursor-at", "cursor-event-id")

    fun requireNoQuery(rawQuery: String?) {
        strictQuery(rawQuery, emptySet())
    }

    fun eventsQuery(rawQuery: String?): PlayerTelemetryQuery {
        val query = strictQuery(rawQuery, eventQueryFields)
        val cursorAt = query["cursor-at"]
        val cursorEventId = query["cursor-event-id"]
        require((cursorAt == null) == (cursorEventId == null)) {
            "cursor-at and cursor-event-id must be supplied together"
        }
        return PlayerTelemetryQuery(
            fromInclusive = parseTime(required(query, "from"), "from"),
            untilExclusive = parseTime(required(query, "until"), "until"),
            playerId = query["player-id"],
            playerName = query["player-name"],
            sessionId = query["session-id"],
            server = query["server"],
            source = query["source"],
            event = query["event"],
            subject = query["subject"],
            includeQa = parseBoolean(query["include-qa"], "include-qa"),
            limit = parseLimit(query["limit"]),
            cursor = if (cursorAt == null) null else PlayerTelemetryCursor(
                occurredAt = parseTime(cursorAt, "cursor-at"),
                eventId = cursorEventId!!,
            ),
        )
    }

    fun summaryQuery(rawQuery: String?): PlayerTelemetrySummaryQuery {
        val query = strictQuery(rawQuery, summaryQueryFields)
        return PlayerTelemetrySummaryQuery(
            fromInclusive = parseTime(required(query, "from"), "from"),
            untilExclusive = parseTime(required(query, "until"), "until"),
            playerId = query["player-id"],
            playerName = query["player-name"],
            sessionId = query["session-id"],
            server = query["server"],
            source = query["source"],
            event = query["event"],
            subject = query["subject"],
            includeQa = parseBoolean(query["include-qa"], "include-qa"),
            limit = parseLimit(query["limit"]),
        )
    }

    fun catalog(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to PlayerTelemetryEvent.CURRENT_SCHEMA_VERSION,
        "timeSemantics" to "from is inclusive; until is exclusive; timestamps require an explicit UTC offset and millisecond precision",
        "sampling" to linkedMapOf(
            "activity" to "movement/idle/resume transitions; idle-after-seconds defaults to 60",
            "position.sample" to "sample-interval-seconds defaults to 1; movement is coalesced by position-interval-seconds (15)",
            "inventory" to "open/close transitions and click actions; no periodic inventory polling",
            "ui" to "open/impression/click/attempt/blocked/close/censored/no_choice events; identifiers and numeric/enumerated details only",
            "resourcePack" to "status timing includes an explicit timingBasis; accepted-to-status is not client-side full download latency",
        ),
        "eventFamilies" to listOf(
            mapOf("source" to "player", "events" to listOf("session.start", "session.resume", "session.end", "activity.first_movement", "activity.idle", "activity.resume", "position.sample", "connection.kick", "world.enter", "teleport", "command", "block.break", "block.place", "interact", "craft.attempt", "combat.kill", "death", "advancement", "item.consume", "fishing", "chat.sent", "inventory.click", "npc.click", "furniture.place_attempt", "furniture.placed", "furniture.break", "furniture.interact")),
            mapOf("source" to "inventory", "events" to listOf("inventory.open", "inventory.close")),
            mapOf("source" to "paper", "events" to listOf("resource_pack.status")),
            mapOf("source" to "ui", "events" to listOf("ui.open", "ui.impression", "ui.click", "ui.attempt", "ui.blocked", "ui.close", "ui.censored", "ui.no_choice")),
            mapOf("source" to "arc", "events" to listOf("node.started", "mechanic.observation", "mechanic.outcome", "feature.interest")),
            mapOf("source" to "mounts", "events" to listOf("mount.ride_started", "mount.ride_ended", "mount.purchase.<persistedstage>")),
            mapOf("source" to "treasure", "events" to listOf("treasure.chest_claimed")),
            mapOf("source" to "dungeons", "events" to listOf("dungeon.started", "dungeon.finished")),
            mapOf("source" to "economy", "events" to listOf("economy.observed")),
            mapOf("source" to "proxy", "events" to listOf("connection.login", "connection.resume", "connection.end", "server.connected", "server.denied", "server.kick", "resource_pack.sent", "resource_pack.status")),
            mapOf("source" to "arcecojobs", "events" to listOf("job_boost_purchased", "job_boost_activated", "work_blocked_afk", "work_blocked_farm", "mechanic.observation")),
            mapOf("source" to "arcfarms", "events" to listOf("farm_reward_claimed", "worksite_started", "worksite_start_rejected", "worksite_completed", "mechanic.observation")),
            mapOf("source" to "arcvotes", "events" to listOf("vote_reward_claimed", "mechanic.observation")),
            mapOf("source" to "arcranks", "events" to listOf("rank_promotion_succeeded", "rank_perk_selected", "rank_kit_claimed", "daily_quest_selected", "daily_quest_unselected", "daily_quest_replaced", "daily_quest_progress_checkpoint", "daily_quest_completed", "daily_quest_reward_claimed", "daily_quest_reward_recovery", "mechanic.observation")),
            mapOf("source" to "arcduels", "events" to listOf("duel_completed", "mechanic.observation")),
            mapOf("source" to "arcevents", "events" to listOf("event_completed", "mechanic.observation")),
            mapOf("source" to "arcgiveaways", "events" to listOf("giveaway_item_granted", "mechanic.observation")),
            mapOf("source" to "arcbuilder", "events" to listOf("mechanic.observation")),
            mapOf("source" to "arcexcellentcrates", "events" to listOf("crate_open_started", "crate_reward_selected", "crate_reward_claimed", "crate_reward_mail_pending", "crate_open_failed", "crate_open_recovery_required", "crate_reward_recovery_required", "crate_opening_recovered", "crate_key_purchase_started", "crate_key_purchase_delivered", "crate_key_purchase_cancelled", "crate_key_purchase_review", "crate_key_purchase_outcome_unknown", "crate_key_purchase_recovery_required")),
            mapOf("source" to "trails", "events" to listOf("trail_enabled", "mechanic.observation")),
        ),
        "dataBoundaries" to listOf(
            "Chat event records contain no message text; command capture is root-only.",
            "Paper session IDs represent backend visits; Proxy connection IDs represent proxy sessions. They are distinct scopes unless an event supplies an explicit shared correlation ID.",
            "Resource-pack timing is reported with its timingBasis; direct Proxy plugin sends do not expose full client download/apply latency through the standard API.",
            "Resource-pack capture on Proxy records backend-origin sends only; direct plugin-to-client sends are not observable here.",
            "Some source modules only emit events after their own durable or committed transition. Telemetry itself is an asynchronous observation and does not certify the upstream operation or financial effect.",
            "Coverage metadata aggregates registered event nodes. A never-registered or removed node is not represented; nodesObserved is not a configured-server inventory.",
            "crate_offers_rerolled is not currently emitted because its producer transition has no service callsite.",
            "Event names are observations from their owning module and may expand as module versions change; use the returned source and event fields when filtering.",
            "Collection starts at activation. Existing logs and aggregates are not replayed into this event history.",
        ),
    )

    fun page(page: ru.arc.telemetry.PlayerTelemetryPage): Map<String, Any?> = linkedMapOf(
        "from" to timestamp(page.fromInclusive),
        "fromEpochMs" to page.fromInclusive,
        "until" to timestamp(page.untilExclusive),
        "untilEpochMs" to page.untilExclusive,
        "coverageFrom" to page.coverageFrom?.let(::timestamp),
        "coverageFromEpochMs" to page.coverageFrom,
        "coverageGapFrom" to page.coverageGapFrom?.let(::timestamp),
        "coverageGapFromEpochMs" to page.coverageGapFrom,
        "retainedFrom" to timestamp(page.retainedFrom),
        "retainedFromEpochMs" to page.retainedFrom,
        "nodesObserved" to page.nodesObserved,
        "oldestNodeLastSeenAt" to page.oldestNodeLastSeenAt?.let(::timestamp),
        "oldestNodeLastSeenAtEpochMs" to page.oldestNodeLastSeenAt,
        "oldestNodeCoverageThrough" to page.oldestNodeCoverageThrough?.let(::timestamp),
        "oldestNodeCoverageThroughEpochMs" to page.oldestNodeCoverageThrough,
        "historyCovered" to page.historyCovered,
        "events" to page.events.map(::event),
        "hasMore" to page.hasMore,
        "paginationComplete" to page.complete,
        "nextCursor" to page.nextCursor?.let { mapOf("occurredAt" to timestamp(it.occurredAt), "occurredAtEpochMs" to it.occurredAt, "eventId" to it.eventId) },
    )

    fun summary(summary: ru.arc.telemetry.PlayerTelemetrySummary): Map<String, Any?> = linkedMapOf(
        "from" to timestamp(summary.fromInclusive),
        "fromEpochMs" to summary.fromInclusive,
        "until" to timestamp(summary.untilExclusive),
        "untilEpochMs" to summary.untilExclusive,
        "coverageFrom" to summary.coverageFrom?.let(::timestamp),
        "coverageFromEpochMs" to summary.coverageFrom,
        "coverageGapFrom" to summary.coverageGapFrom?.let(::timestamp),
        "coverageGapFromEpochMs" to summary.coverageGapFrom,
        "retainedFrom" to timestamp(summary.retainedFrom),
        "retainedFromEpochMs" to summary.retainedFrom,
        "nodesObserved" to summary.nodesObserved,
        "oldestNodeLastSeenAt" to summary.oldestNodeLastSeenAt?.let(::timestamp),
        "oldestNodeLastSeenAtEpochMs" to summary.oldestNodeLastSeenAt,
        "oldestNodeCoverageThrough" to summary.oldestNodeCoverageThrough?.let(::timestamp),
        "oldestNodeCoverageThroughEpochMs" to summary.oldestNodeCoverageThrough,
        "historyCovered" to summary.historyCovered,
        "rows" to summary.rows.map { row ->
            mapOf("utcDay" to row.utcDay, "server" to row.server, "source" to row.source, "event" to row.event, "count" to row.count, "uniquePlayers" to row.uniquePlayers)
        },
        "hasMore" to summary.hasMore,
        "paginationComplete" to !summary.hasMore,
    )

    fun <T> await(store: PlayerTelemetryStore, future: CompletableFuture<T>): T {
        if (!store.health().sqlReady) throw TelemetryUnavailableException()
        return try {
            future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is IllegalArgumentException) throw cause
            throw TelemetryUnavailableException(cause)
        }
    }

    private fun event(event: PlayerTelemetryEvent): Map<String, Any?> = linkedMapOf(
        "eventId" to event.eventId,
        "schemaVersion" to event.schemaVersion,
        "occurredAt" to timestamp(event.occurredAt),
        "occurredAtEpochMs" to event.occurredAt,
        "server" to event.server,
        "playerId" to event.playerId,
        "playerName" to event.playerName,
        "sessionId" to event.sessionId,
        "source" to event.source,
        "event" to event.event,
        "subject" to event.subject,
        "operationId" to event.operationId,
        "world" to event.world,
        "x" to event.x,
        "y" to event.y,
        "z" to event.z,
        "qa" to event.qa,
        "attributes" to event.attributes,
    )

    private fun strictQuery(rawQuery: String?, allowed: Set<String>): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { part ->
            require(part.isNotEmpty()) { "Query string contains an empty parameter" }
            val separator = part.indexOf('=')
            require(separator > 0) { "Query parameters must use key=value form" }
            val key = decode(part.substring(0, separator))
            val value = decode(part.substring(separator + 1))
            require(key in allowed) { "Unknown query parameter '$key'" }
            require(result.putIfAbsent(key, value) == null) { "Duplicate query parameter '$key'" }
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Query string contains invalid percent encoding")
    }

    private fun required(query: Map<String, String>, key: String): String =
        query[key]?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("'$key' is required")

    private fun parseBoolean(value: String?, name: String): Boolean = when (value) {
        null -> false
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("'$name' must be true or false")
    }

    private fun parseLimit(value: String?): Int {
        if (value == null) return 100
        require(value.matches(Regex("[0-9]{1,4}"))) { "'limit' must be an integer between 1 and $MAX_LIMIT" }
        val limit = value.toInt()
        require(limit in 1..MAX_LIMIT) { "'limit' must be between 1 and $MAX_LIMIT" }
        return limit
    }

    private fun parseTime(value: String, field: String): Long {
        val instant = try {
            OffsetDateTime.parse(value).toInstant()
        } catch (failure: Exception) {
            throw IllegalArgumentException("'$field' must be an ISO-8601 timestamp with an explicit UTC offset")
        }
        require(instant.nano % 1_000_000 == 0) { "'$field' must use millisecond precision" }
        return try {
            instant.toEpochMilli()
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("'$field' is outside the supported epoch range")
        }
    }

    private fun timestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
}
