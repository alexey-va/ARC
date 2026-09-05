package ru.arc.metrics

import com.google.gson.Gson
import ru.arc.metrics.core.MetricPoint
import ru.arc.util.Common
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.floor

data class ProductJourneySnapshot(
    val firstJoinAt: Long?,
    val firstMenuAt: Long?,
    val firstPathAt: Map<ProductPath, Long>,
    val firstGameplayOutcomeAt: Map<ProductPath, Long> = emptyMap(),
)

data class ProductStoreApplyResult(
    val changed: Boolean,
    val journeyBefore: ProductJourneySnapshot?,
)

/**
 * Bounded rolling product state. Player identity is a SHA-256 pseudonym and is
 * never emitted as a metric label. The state is small enough to persist
 * atomically and exact enough to calculate unique, funnel and retention gauges.
 */
class ProductInterestStore private constructor(
    private val path: Path,
    private val config: ProductInterestConfig,
    private val gson: Gson,
    private val players: MutableMap<String, PlayerRecord>,
    private var lastSavedAt: Long,
    val recoveredInvalidPath: Path? = null,
) {
    private var uiDroppedEvents: Long = 0
    private var capacityEvictions: Long = 0
    private var resultObservationStartedAt: Long = lastSavedAt
    private data class PlayerRecord(
        val id: String,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var firstJoinAt: Long? = null,
        var firstMenuAt: Long? = null,
        val firstPathAt: MutableMap<String, Long> = linkedMapOf(),
        val firstGameplayOutcomeAt: MutableMap<String, Long> = linkedMapOf(),
        var uiAttribution: ProductUiAttribution? = null,
        val days: MutableMap<String, DailyRecord> = linkedMapOf(),
    )

    private data class DailyRecord(
        val date: String,
        val ui: MutableMap<String, ProductUiRow> = linkedMapOf(),
        var sessions: Long = 0,
        var sessionSeconds: Long = 0,
        var activeSeconds: Long = 0,
        var menuOpened: Boolean = false,
        var helpOpened: Boolean = false,
        val activities: MutableSet<String> = linkedSetOf(),
        val features: MutableSet<String> = linkedSetOf(),
        val pathInterests: MutableSet<String> = linkedSetOf(),
        val pathChoices: MutableSet<String> = linkedSetOf(),
        val outcomes: MutableSet<String> = linkedSetOf(),
        val systems: MutableSet<String> = linkedSetOf(),
        val actionCounts: MutableMap<String, Long> = linkedMapOf(),
        val details: MutableMap<String, MutableMap<String, DetailRecord>> = linkedMapOf(),
        val exits: MutableMap<String, ExitRecord> = linkedMapOf(),
        val recentTrail: MutableList<TrailRecord> = mutableListOf(),
    )

    private data class TrailRecord(
        val occurredAt: Long,
        val step: String,
    )

    private data class DetailRecord(
        val source: String,
        val key: String,
        var display: String? = null,
        var count: Long = 0,
    )

    private data class ExitRecord(
        val source: String,
        val server: String? = null,
        val world: String? = null,
        val command: String? = null,
        val npcId: String? = null,
        var npcName: String? = null,
        val feature: String? = null,
        val activity: String? = null,
        val stage: String,
        val teleportCause: String? = null,
        val connection: String? = null,
        val trail: List<String> = emptyList(),
        var count: Long = 0,
    )

    private data class StoreFile(
        val version: Int = 0,
        val savedAt: Long = 0,
        val players: List<PersistedPlayer>? = null,
        val capacityEvictions: Long = 0,
        val uiDroppedEvents: Long = 0,
        val resultObservationStartedAt: Long? = null,
    )

    private data class PersistedPlayer(
        val id: String? = null,
        val firstSeenAt: Long = 0,
        val lastSeenAt: Long = 0,
        val firstJoinAt: Long? = null,
        val firstMenuAt: Long? = null,
        val firstPathAt: Map<String, Long>? = null,
        val firstGameplayOutcomeAt: Map<String, Long>? = null,
        val days: List<PersistedDay>? = null,
        val uiAttribution: ProductUiAttribution? = null,
    )

    private data class PersistedDay(
        val date: String? = null,
        val ui: List<ProductUiRow>? = null,
        val sessions: Long = 0,
        val sessionSeconds: Long = 0,
        val activeSeconds: Long = 0,
        val menuOpened: Boolean = false,
        val helpOpened: Boolean = false,
        val activities: List<String>? = null,
        val features: List<String>? = null,
        val pathInterests: List<String>? = null,
        val pathChoices: List<String>? = null,
        val outcomes: List<String>? = null,
        val systems: List<String>? = null,
        val actionCounts: Map<String, Long>? = null,
        val details: Map<String, List<PersistedDetail>>? = null,
        val exits: List<PersistedExit>? = null,
        val recentTrail: List<PersistedTrail>? = null,
    )

    private data class PersistedTrail(
        val occurredAt: Long = 0,
        val step: String? = null,
    )

    private data class PersistedDetail(
        val source: String? = null,
        val key: String? = null,
        val display: String? = null,
        val count: Long = 0,
    )

    private data class PersistedExit(
        val source: String? = null,
        val server: String? = null,
        val world: String? = null,
        val command: String? = null,
        val npcId: String? = null,
        val npcName: String? = null,
        val feature: String? = null,
        val activity: String? = null,
        val stage: String? = null,
        val teleportCause: String? = null,
        val connection: String? = null,
        val trail: List<String>? = null,
        val count: Long = 0,
    )

    @Volatile
    private var dirty = false
    private var mutationVersion = 0L
    private val flushLock = ReentrantLock()

    private data class SavePlan(
        val state: StoreFile,
        val mutationVersion: Long,
        val previousLastSavedAt: Long,
    )

    private data class SaveResult(
        val evictedPlayers: Map<String, Long>,
    )

    @Synchronized
    fun journey(player: String): ProductJourneySnapshot? = players[player]?.journey()

    @Synchronized
    fun apply(signal: ProductSignal): ProductStoreApplyResult {
        if (signal.kind == ProductEventKind.MEANINGFUL_OUTCOME && signal.outcome?.isGameplayResult() != true) {
            return ProductStoreApplyResult(false, players[signal.player]?.journey())
        }
        prune(signal.occurredAt)
        val record = player(signal.player, signal.occurredAt)
        val before = record.journey()
        if (signal.occurredAt > record.lastSeenAt) markDirty()
        record.lastSeenAt = maxOf(record.lastSeenAt, signal.occurredAt)
        val day = record.day(dayKey(signal.occurredAt))
        val connection = signal.detail
            ?.takeIf { signal.kind == ProductEventKind.DETAIL && it.type == ProductDetailType.CONNECTION }
            ?.key?.let { key -> TERMINAL_CONNECTIONS.firstOrNull { it.label == key } }
        // Velocity alone confirms a network disconnect. Paper quits also include transfers.
        val networkExitChanged = connection?.let {
            val trail = record.days.values.flatMap { it.recentTrail }.sortedBy { it.occurredAt }.takeLast(MAX_TRAIL_STEPS)
            fun last(kind: String) = trail.lastOrNull { it.step.startsWith("$kind=") }?.step?.substringAfter('=')
            day.recordExit(signal.source, ProductExitContext(
                server = last("server"), world = last("world"), command = last("command"),
                npcId = last("npc"), connection = it, stage = record.exitStage(),
                trail = trail.map { it.step },
            ), config.maxDetailValuesPerPlayerDay)
        } ?: false
        val trailChanged = day.recordTrail(signal)
        var changed = false
        when (signal.kind) {
            ProductEventKind.SESSION_START -> {
                day.sessions++
                changed = true
            }
            ProductEventKind.FIRST_JOIN -> {
                val previous = record.firstJoinAt
                record.firstJoinAt = previous?.let { minOf(it, signal.occurredAt) } ?: signal.occurredAt
                changed = record.firstJoinAt != previous
            }
            ProductEventKind.MENU_OPEN -> {
                changed = !day.menuOpened
                day.menuOpened = true
                val previous = record.firstMenuAt
                record.firstMenuAt = previous?.let { minOf(it, signal.occurredAt) } ?: signal.occurredAt
                changed = changed || record.firstMenuAt != previous
            }
            ProductEventKind.HELP_OPEN -> {
                changed = !day.helpOpened
                day.helpOpened = true
            }
            ProductEventKind.PATH_INTEREST -> {
                changed = day.pathInterests.add(signal.path.label)
                changed = record.firstPathAt.putIfEarlier(signal.path.label, signal.occurredAt) || changed
            }
            ProductEventKind.PATH_CHOICE -> {
                changed = day.pathChoices.add(signal.path.label)
                changed = record.firstPathAt.putIfEarlier(signal.path.label, signal.occurredAt) || changed
            }
            ProductEventKind.ACTIVITY -> {
                signal.activity?.let { changed = day.activities.add(it.label) || changed }
            }
            ProductEventKind.FEATURE_INTEREST -> {
                signal.feature?.let { feature ->
                    changed = day.features.add(feature.label) || changed
                    if (feature.countsAsSystem) changed = day.systems.add(feature.label) || changed
                }
                signal.activity?.let { changed = day.activities.add(it.label) || changed }
                if (signal.path != ProductPath.NONE) {
                    changed = day.pathInterests.add(signal.path.label) || changed
                    changed = record.firstPathAt.putIfEarlier(signal.path.label, signal.occurredAt) || changed
                }
            }
            ProductEventKind.MEANINGFUL_OUTCOME -> {
                signal.outcome?.let { outcome ->
                    changed = day.outcomes.add(outcome.label) || changed
                    changed = day.activities.add(outcome.activity.label) || changed
                    changed = day.pathInterests.add(outcome.path.label) || changed
                    if (outcome.isGameplayResult()) {
                        changed = record.firstGameplayOutcomeAt.putIfEarlier(outcome.path.label, signal.occurredAt) || changed
                    }
                    signal.feature?.takeIf(ProductFeature::countsAsSystem)?.let { changed = day.systems.add(it.label) || changed }
                }
            }
            ProductEventKind.DETAIL -> {
                signal.detail?.let { changed = day.recordDetail(signal.source, it, config.maxDetailValuesPerPlayerDay) || changed }
            }
            ProductEventKind.SESSION_END,
            ProductEventKind.SESSION_CENSORED,
            -> {
                day.sessionSeconds += signal.sessionSeconds.coerceAtLeast(0)
                day.activeSeconds += signal.activeSeconds.coerceIn(0, signal.sessionSeconds)
                changed = signal.sessionSeconds > 0
                signal.systems.forEach { changed = day.systems.add(it) || changed }
                if (signal.kind == ProductEventKind.SESSION_END) {
                    signal.exit?.let {
                        changed = day.recordExit(signal.source, it, config.maxDetailValuesPerPlayerDay) || changed
                    }
                }
                // Preserve the journey across Paper transfers until Velocity disconnects.
            }
        }
        if (signal.kind == ProductEventKind.MEANINGFUL_OUTCOME) {
            val feature = signal.feature ?: signal.outcome?.uiFeature()
            record.uiAttribution?.takeIf { !it.result && it.feature != null && it.feature == feature?.label &&
                signal.occurredAt - it.occurredAt in 0..600_000 }?.let { click ->
                record.uiRow(click)?.add("result")
                click.result = true
                changed = true
            }
        }
        if (connection != null) record.days.values.forEach { it.recentTrail.clear() }
        val mutated = changed || trailChanged || networkExitChanged
        if (mutated) markDirty()
        return ProductStoreApplyResult(changed, before)
    }

    /** Actual UI observations, independent from inferred command interest. */
    @Synchronized
    fun applyUi(signal: ProductUiSignal): Boolean {
        if (!ProductUiCodec.valid(signal)) return false
        prune(signal.occurredAt)
        val record = player(signal.player, signal.occurredAt)
        record.lastSeenAt = maxOf(record.lastSeenAt, signal.occurredAt)
        val day = record.day(dayKey(signal.occurredAt))
        val key = "${signal.surface}|${signal.revision}|${signal.button}"
        if (key !in day.ui && day.ui.size >= 128) {
            uiDroppedEvents++
            markDirty()
            return false
        }
        val row = day.ui.getOrPut(key) { ProductUiRow(signal.surface, signal.revision, signal.button, signal.feature) }
        row.add(signal.kind.name.lowercase())
        row.durationMillis += signal.durationMillis
        if (signal.feature != null) row.feature = signal.feature
        if (signal.kind == ProductUiKind.OPEN) {
            record.firstMenuAt = minOf(record.firstMenuAt ?: signal.occurredAt, signal.occurredAt)
            day.menuOpened = true
            record.uiAttribution?.takeIf { !it.destination && it.surface != signal.surface &&
                signal.occurredAt - it.occurredAt in 0..30_000 }?.let { click ->
                record.uiRow(click)?.add("destination")
                click.destination = true
            }
        }
        if (signal.kind == ProductUiKind.CLICK) record.uiAttribution = ProductUiAttribution(
            signal.surface, signal.revision, signal.button, signal.feature, signal.occurredAt)
        markDirty()
        return true
    }

    private fun PlayerRecord.uiRow(click: ProductUiAttribution): ProductUiRow? =
        days[dayKey(click.occurredAt)]?.ui?.get("${click.surface}|${click.revision}|${click.button}")

    private data class UiAggregate(val surface: String, val revision: String, val button: String,
        var feature: String? = null, val events: MutableMap<String, Long> = linkedMapOf(),
        val players: MutableMap<String, MutableSet<String>> = linkedMapOf(), var durationMillis: Long = 0)

    private fun uiAggregates(now: Long, days: Int): List<UiAggregate> {
        val end = localDate(now)
        val start = end.minusDays(days.toLong() - 1)
        val rows = linkedMapOf<String, UiAggregate>()
        players.values.forEach { player ->
            player.window(start, end).orEmpty().forEach { day ->
                day.ui.values.forEach { item ->
                    val row = rows.getOrPut(item.key) { UiAggregate(item.surface, item.revision, item.button) }
                    row.feature = item.feature ?: row.feature
                    row.durationMillis += item.durationMillis
                    item.counts.forEach { (event, count) ->
                        row.events[event] = (row.events[event] ?: 0) + count
                        if (count > 0) row.players.getOrPut(event) { linkedSetOf() }.add(player.id)
                    }
                }
            }
        }
        return rows.values.sortedWith(compareByDescending<UiAggregate> { (it.events["click"] ?: 0) + (it.events["attempt"] ?: 0) }
            .thenByDescending { it.events["open"] ?: it.events["impression"] ?: 0 }.thenBy { it.surface }.thenBy { it.button })
    }

    private fun uiReport(now: Long, days: Int, limit: Int): Map<String, Any?> {
        val rows = uiAggregates(now, days)
        return linkedMapOf(
            "definition" to "Actual menu views and rendered button impressions; core accepted/blocked actions and native physical attempts. No-choice means user close without a button press",
            "attribution" to "Last accepted click: another surface within 30 seconds; matching instrumented gameplay result within 10 minutes. Temporal association, not causality",
            "complete" to (uiDroppedEvents == 0L && rows.size <= limit), "droppedEvents" to uiDroppedEvents,
            "observedRows" to rows.size, "returnedRows" to rows.size.coerceAtMost(limit),
            "rows" to rows.take(limit).map { row -> linkedMapOf(
                "surface" to row.surface, "revision" to row.revision, "button" to row.button,
                "feature" to row.feature, "resultAttributionSupported" to (ProductOutcome.entries.any { it.uiFeature()?.label == row.feature && row.feature != null } && !row.surface.startsWith("zmenu:")),
                "acceptedClicksSupported" to !row.surface.startsWith("zmenu:"),
                "events" to row.events, "uniquePlayers" to row.players.mapValues { it.value.size },
                "uniqueAttemptRate" to (row.players["impression"]?.size?.takeIf { it > 0 }?.let { (row.players["attempt"]?.size ?: 0).toDouble() / it }),
                "meanVisitSeconds" to (row.events["close"]?.takeIf { it > 0 }?.let { row.durationMillis / 1000.0 / it }),
                "uniqueClickRate" to (row.players["impression"]?.size?.takeIf { it > 0 }?.let { (row.players["click"]?.size ?: 0).toDouble() / it }),
            ) },
        )
    }

    /** Record local high-volume actions without publishing every action to Redis. */
    @Synchronized
    fun recordAction(
        player: String,
        action: ProductAction,
        occurredAt: Long,
    ): Boolean {
        prune(occurredAt)
        val record = player(player, occurredAt)
        record.lastSeenAt = maxOf(record.lastSeenAt, occurredAt)
        val day = record.day(dayKey(occurredAt))
        val previous = day.actionCounts[action.label] ?: 0L
        day.actionCounts[action.label] = previous + 1
        markDirty()
        return previous == 0L
    }

    @Synchronized
    fun actionCount(
        player: String,
        action: ProductAction,
        occurredAt: Long,
    ): Long = players[player]?.days?.get(dayKey(occurredAt))?.actionCounts?.get(action.label) ?: 0L

    fun flush(
        now: Long,
        force: Boolean = false,
    ): Boolean {
        if (force) {
            flushLock.lock()
        } else if (!flushLock.tryLock()) {
            return false
        }
        try {
            val plan =
                synchronized(this) {
                    if (!dirty) return false
                    if (!force && now - lastSavedAt < config.persistIntervalSeconds * 1_000L) return false
                    prune(now)
                    SavePlan(persistedSnapshot(now), mutationVersion, lastSavedAt).also { lastSavedAt = now }
                }
            val result =
                try {
                    save(plan.state)
                } catch (failure: Throwable) {
                    synchronized(this) {
                        if (lastSavedAt == now) lastSavedAt = plan.previousLastSavedAt
                    }
                    throw failure
                }
            synchronized(this) {
                if (mutationVersion == plan.mutationVersion) {
                    result.evictedPlayers.forEach { (id, _) ->
                        if (players.remove(id) != null) capacityEvictions++
                    }
                    dirty = false
                }
            }
            return true
        } finally {
            flushLock.unlock()
        }
    }

    @Synchronized
    fun snapshot(
        now: Long,
        scope: String,
    ): List<MetricPoint> {
        prune(now)
        val today = localDate(now)
        val points = mutableListOf<MetricPoint>()
        points += point("arc_product_measurement_version", "Product measurement semantics version", 3, scope)
        points += point("arc_product_ui_dropped_events", "UI events discarded by the per-player bounded store", uiDroppedEvents, scope)
        WINDOWS.forEach { days ->
            val window = "${days}d"
            val uiRows = uiAggregates(now, days)
            uiRows.take(200).forEach { row ->
                ProductUiRow.EVENTS.forEach { event ->
                    val labels = arrayOf("window" to window, "surface" to row.surface, "revision" to row.revision,
                        "button" to row.button, "event" to event)
                    points += point("arc_product_ui_events", "Observed UI events in the rolling window", row.events[event] ?: 0, scope, *labels)
                    points += point("arc_product_ui_players", "Unique UI participants in the rolling window", row.players[event]?.size ?: 0, scope, *labels)
                }
            }
            points += point("arc_product_ui_metric_rows", "UI rows exposed in Prometheus (bounded at 200)", uiRows.size.coerceAtMost(200), scope, "window" to window)
            points += point("arc_product_ui_observed_rows", "All observed UI rows before Prometheus truncation", uiRows.size, scope, "window" to window)
            val start = today.minusDays(days.toLong() - 1)
            val records = players.values.mapNotNull { player -> player.window(start, today)?.let { player to it } }
            val newPlayers = records.count { (player) -> player.firstJoinAt?.let(::localDate)?.let { !it.isBefore(start) && !it.isAfter(today) } == true }
            val returning = records.count { (_, daily) -> daily.size >= 2 }
            val multisystem = records.count { (_, daily) -> daily.flatMap { it.systems }.toSet().size >= 2 }
            points += point("arc_product_unique_players", "Unique pseudonymous players in the rolling window", records.size, scope, "window" to window)
            points += point("arc_product_new_players", "First-join players in the rolling window", newPlayers, scope, "window" to window)
            points += point("arc_product_returning_players", "Players active on at least two calendar days", returning, scope, "window" to window)
            points += point("arc_product_multisystem_players", "Players using at least two bounded product systems", multisystem, scope, "window" to window)
            ProductFeature.entries.forEach { feature ->
                points += point(
                    "arc_product_feature_unique_players",
                    "Unique players expressing interest in a bounded feature",
                    records.count { (_, daily) -> daily.any { feature.label in it.features } },
                    scope,
                    "window" to window,
                    "feature" to feature.label,
                )
            }
            ProductActivity.entries.forEach { activity ->
                points += point(
                    "arc_product_activity_unique_players",
                    "Unique players observed in a bounded activity",
                    records.count { (_, daily) -> daily.any { activity.label in it.activities } },
                    scope,
                    "window" to window,
                    "activity" to activity.label,
                )
            }
            ProductOutcome.entries.filter(ProductOutcome::isGameplayResult).forEach { outcome ->
                points += point(
                    "arc_product_outcome_unique_players",
                    "Unique players reaching a bounded meaningful outcome",
                    records.count { (_, daily) -> daily.any { outcome.label in it.outcomes } },
                    scope,
                    "window" to window,
                    "outcome" to outcome.label,
                    "path" to outcome.path.label,
                )
            }
            ProductPath.entries.filterNot { it == ProductPath.NONE }.forEach { path ->
                val interest = records.count { (_, daily) -> daily.any { path.label in it.pathInterests } }
                val choice = records.count { (_, daily) -> daily.any { path.label in it.pathChoices } }
                val outcome = records.count { (_, daily) -> daily.any { day -> day.outcomes.any { label -> outcomePath(label) == path } } }
                points += point("arc_product_path_players", "Unique players by path signal", interest, scope, "window" to window, "path" to path.label, "signal" to "interest")
                points += point("arc_product_path_players", "Unique players by path signal", choice, scope, "window" to window, "path" to path.label, "signal" to "choice")
                points += point("arc_product_path_players", "Unique players by path signal", outcome, scope, "window" to window, "path" to path.label, "signal" to "outcome")
            }
            points += point(
                "arc_product_sessions",
                "Completed and started product sessions",
                records.sumOf { (_, daily) -> daily.sumOf { it.sessions } },
                scope,
                "window" to window,
            )
            points += point(
                "arc_product_session_time_seconds",
                "Completed session time in the rolling window",
                records.sumOf { (_, daily) -> daily.sumOf { it.sessionSeconds } },
                scope,
                "window" to window,
            )
            points += point(
                "arc_product_active_time_seconds",
                "Observed active session time in the rolling window",
                records.sumOf { (_, daily) -> daily.sumOf { it.activeSeconds } },
                scope,
                "window" to window,
            )
            addFunnel(points, records.map { it.first }, start, today, window, scope)
            ProductExitStage.entries.forEach { stage ->
                points += point("arc_product_network_exits", "Velocity-confirmed active disconnects by observed journey stage",
                    records.sumOf { (_, daily) -> daily.sumOf { day -> day.exits.values.filter {
                        it.connection == ProductConnection.DISCONNECT_ACTIVE.label && it.stage == stage.label
                    }.sumOf { it.count } } }, scope, "window" to window, "stage" to stage.label)
            }
        }
        addRetention(points, today, scope)
        activationReport(now).forEach { row ->
            val within = when (row["withinSeconds"]) { 600L -> "10m"; 3_600L -> "1h"; else -> "24h" }
            points += point("arc_product_activation_eligible_players", "New players with the full first-outcome observation budget", row["eligiblePlayers"] as Int, scope, "within" to within)
            points += point("arc_product_activation_reached_players", "New players reaching a confirmed outcome within budget", row["reachedPlayers"] as Int, scope, "within" to within)
            listOf("0.5" to "medianSecondsAmongReached", "0.9" to "p90SecondsAmongReached").forEach { (quantile, key) ->
                points += point("arc_product_activation_latency_seconds", "First-outcome latency among reached players only", row[key] as? Double ?: Double.NaN, scope, "within" to within, "quantile" to quantile)
            }
        }
        val allDays = players.values.flatMap { it.days.keys }.mapNotNull(::parseDate)
        points += point("arc_product_telemetry_state_players", "Pseudonymous players retained in product telemetry state", players.size, scope)
        points += point("arc_product_telemetry_state_days", "Player-day aggregates retained in product telemetry state", players.values.sumOf { it.days.size }, scope)
        points += point("arc_product_telemetry_capacity_evictions", "Players evicted to preserve configured telemetry bounds", capacityEvictions, scope)
        points += point(
            "arc_product_telemetry_oldest_day_timestamp_seconds",
            "Oldest retained product telemetry day",
            allDays.minOrNull()?.atStartOfDay(config.zoneId)?.toEpochSecond() ?: 0,
            scope,
        )
        points += point(
            "arc_product_telemetry_newest_day_timestamp_seconds",
            "Newest retained product telemetry day",
            allDays.maxOrNull()?.atStartOfDay(config.zoneId)?.toEpochSecond() ?: 0,
            scope,
        )
        return points
    }

    /**
     * Aggregated operator report. Pseudonyms never leave this store; each row
     * contains only a bounded journey detail plus event and unique-player counts.
     */
    @Synchronized
    fun report(
        now: Long,
        days: Int,
        limit: Int,
    ): Map<String, Any?> {
        val safeDays = days.coerceIn(1, config.retentionDays)
        val safeLimit = limit.coerceIn(1, 100)
        prune(now)
        val end = localDate(now)
        val start = end.minusDays(safeDays.toLong() - 1)
        val selected =
            players.values.mapNotNull { player ->
                player.window(start, end)?.let { player.id to it }
            }

        val dimensions = linkedMapOf<String, Any?>()
        ProductDetailType.entries.forEach { type ->
            val aggregate = linkedMapOf<String, ReportDetail>()
            selected.forEach { (playerId, playerDays) ->
                val seen = linkedSetOf<String>()
                playerDays.forEach { day ->
                    day.details[type.label].orEmpty().values.forEach { detail ->
                        val id = detailId(detail.source, detail.key)
                        val row = aggregate.getOrPut(id) { ReportDetail(detail.source, detail.key, detail.display) }
                        row.events += detail.count
                        if (!detail.display.isNullOrBlank()) row.display = detail.display
                        seen += id
                    }
                }
                seen.forEach { aggregate.getValue(it).players += playerId }
            }
            dimensions[type.label] =
                aggregate.values
                    .sortedWith(compareByDescending<ReportDetail> { it.events }.thenByDescending { it.players.size }.thenBy { it.source }.thenBy { it.key })
                    .take(safeLimit)
                    .map { row ->
                        linkedMapOf<String, Any?>(
                            "server" to row.source,
                            "value" to row.key,
                            "display" to row.display,
                            "events" to row.events,
                            "uniquePlayers" to row.players.size,
                        ).filterValues { it != null }
                    }
        }

        val exits = linkedMapOf<String, ReportExit>()
        selected.forEach { (playerId, playerDays) ->
            val seen = linkedSetOf<String>()
            playerDays.forEach { day ->
                day.exits.forEach { (id, exit) ->
                    val row = exits.getOrPut(id) { ReportExit(exit) }
                    row.sessions += exit.count
                    if (!exit.npcName.isNullOrBlank()) row.npcName = exit.npcName
                    seen += id
                }
            }
            seen.forEach { exits.getValue(it).players += playerId }
        }

        return linkedMapOf(
            "version" to VERSION,
            "measurementVersion" to 3,
            "resultObservationStartedAt" to resultObservationStartedAt,
            "generatedAt" to now,
            "window" to linkedMapOf("days" to safeDays, "from" to start.toString(), "through" to end.toString()),
            "complete" to (capacityEvictions == 0L && recoveredInvalidPath == null),
            "quality" to linkedMapOf(
                "capacityEvictionsSinceStartup" to capacityEvictions,
                "recoveredInvalidState" to (recoveredInvalidPath != null),
                "historyGuarantee" to "Bounded observed data; missing history cannot be reconstructed",
                "sessionUnit" to "Paper backend visits, including server transfers",
                "exitInterpretation" to "Observed context, not a proven reason for leaving",
            ),
            "cohortDefinition" to "Observed first visit to primary Paper entry node; not account registration",
            "ui" to uiReport(now, safeDays, safeLimit),
            "activation" to activationReport(now, safeDays),
            "retention" to retentionReport(end, safeDays),
            "privacy" to linkedMapOf(
                "identity" to "SHA-256 pseudonyms retained internally; identities are not returned",
                "commands" to "command roots only; arguments are discarded before persistence",
                "location" to "world names only; coordinates are not retained",
                "chat" to "message content is never retained",
            ),
            "players" to selected.size,
            "sessions" to selected.sumOf { (_, playerDays) -> playerDays.sumOf { it.sessions } },
            "detailEvents" to selected.sumOf { (_, playerDays) -> playerDays.sumOf { day -> day.details.values.sumOf { bucket -> bucket.values.sumOf { it.count } } } },
            "dimensions" to dimensions,
            "exitContexts" to
                exits.values.filter { it.connection == null }
                    .sortedWith(compareByDescending<ReportExit> { it.sessions }.thenByDescending { it.players.size }.thenBy { it.source })
                    .take(safeLimit)
                    .map(ReportExit::asMap),
            "networkExitContexts" to exits.values.filter { it.connection != null }
                .sortedWith(compareByDescending<ReportExit> { it.sessions }.thenBy { it.source })
                .take(safeLimit).map(ReportExit::asMap),
        )
    }

    private data class ReportDetail(
        val source: String,
        val key: String,
        var display: String?,
        var events: Long = 0,
        val players: MutableSet<String> = linkedSetOf(),
    )

    private data class ReportExit(
        val source: String,
        val server: String?,
        val world: String?,
        val command: String?,
        val npcId: String?,
        var npcName: String?,
        val feature: String?,
        val activity: String?,
        val stage: String,
        val teleportCause: String?,
        val connection: String?,
        val trail: List<String>,
        var sessions: Long = 0,
        val players: MutableSet<String> = linkedSetOf(),
    ) {
        constructor(exit: ExitRecord) : this(
            source = exit.source,
            server = exit.server,
            world = exit.world,
            command = exit.command,
            npcId = exit.npcId,
            npcName = exit.npcName,
            feature = exit.feature,
            activity = exit.activity,
            stage = exit.stage,
            teleportCause = exit.teleportCause,
            connection = exit.connection,
            trail = exit.trail,
        )

        fun asMap(): Map<String, Any?> =
            linkedMapOf<String, Any?>(
                "server" to (server ?: source),
                "source" to source,
                "world" to world,
                "command" to command,
                "npcId" to npcId,
                "npcName" to npcName,
                "feature" to feature,
                "activity" to activity,
                "stage" to stage,
                "teleportCause" to teleportCause,
                "connection" to connection,
                "trail" to trail.takeIf { it.isNotEmpty() },
                "sessions" to sessions,
                "uniquePlayers" to players.size,
            ).filterValues { it != null }
    }

    private fun addFunnel(
        points: MutableList<MetricPoint>,
        records: List<PlayerRecord>,
        start: LocalDate,
        today: LocalDate,
        window: String,
        scope: String,
    ) {
        val cohort = records.filter { player -> player.firstJoinAt?.let(::localDate)?.let { !it.isBefore(start) && !it.isAfter(today) } == true }
        fun funnelPoint(
            value: Int,
            stage: String,
            path: String,
        ) = point(
            "arc_product_new_player_funnel",
            "First-join cohort reaching a bounded funnel stage",
            value,
            scope,
            "window" to window,
            "stage" to stage,
            "path" to path,
        )

        points += funnelPoint(cohort.size, "first_join", "none")
        points += funnelPoint(cohort.count { it.firstMenuAt != null }, "menu_open", "none")
        points += funnelPoint(cohort.count { it.firstPathAt.isNotEmpty() }, "path_interest", "any")
        points += funnelPoint(cohort.count { player -> player.days.values.any { it.pathChoices.isNotEmpty() } }, "path_choice", "any")
        points += funnelPoint(cohort.count { it.firstGameplayOutcomeAt.isNotEmpty() }, "outcome", "any")
        ProductPath.entries.filterNot { it == ProductPath.NONE }.forEach { path ->
            points += funnelPoint(cohort.count { path.label in it.firstPathAt }, "path_interest", path.label)
            points += funnelPoint(
                cohort.count { player -> player.days.values.any { path.label in it.pathChoices } },
                "path_choice",
                path.label,
            )
            points += funnelPoint(cohort.count { path.label in it.firstGameplayOutcomeAt }, "outcome", path.label)
        }
    }

    private fun addRetention(
        points: MutableList<MetricPoint>,
        today: LocalDate,
        scope: String,
    ) {
        retentionReport(today).forEach { row ->
            points += point("arc_product_retention_eligible_players", "First-join cohort with fully closed retention days", row.getValue("eligiblePlayers") as Int, scope, "horizon" to row.getValue("horizon").toString())
            points += point("arc_product_retained_players", "Players starting a return visit in the specified retention period", row.getValue("returnedPlayers") as Int, scope, "horizon" to row.getValue("horizon").toString())
        }
    }

    private fun retentionReport(today: LocalDate, cohortDays: Int = config.retentionDays): List<Map<String, Any>> = listOf("d1", "d7", "w1").map { horizon ->
        val days = if (horizon == "d1") 1L else 7L
        val oldest = today.minusDays(cohortDays.toLong() - 1)
        val eligible = players.values.filter { player -> player.firstJoinAt?.let(::localDate)?.let {
            !it.isBefore(oldest) && it.plusDays(days).isBefore(today)
        } == true }
        val returned = eligible.count { player ->
            val first = localDate(checkNotNull(player.firstJoinAt))
            player.days.values.any { day ->
                val date = LocalDate.parse(day.date)
                day.sessions > 0 && if (horizon == "w1") date.isAfter(first) && !date.isAfter(first.plusDays(7))
                else date == first.plusDays(days)
            }
        }
        linkedMapOf("horizon" to horizon, "eligiblePlayers" to eligible.size, "returnedPlayers" to returned)
    }

    /** Fixed observation budgets avoid penalizing newcomers whose observation window is still open. */
    private fun activationReport(now: Long, cohortDays: Int = config.retentionDays): List<Map<String, Any?>> = listOf(600L, 3_600L, 86_400L).map { budget ->
        val oldest = localDate(now).minusDays(cohortDays.toLong() - 1)
        val eligible = players.values.filter { player -> player.firstJoinAt?.let {
            it >= resultObservationStartedAt && !localDate(it).isBefore(oldest) && it <= now - budget * 1_000
        } == true }
        val latencies = eligible.mapNotNull { player ->
            val joined = checkNotNull(player.firstJoinAt)
            player.firstGameplayOutcomeAt.values.filter { it >= joined }.minOrNull()?.let { (it - joined) / 1_000.0 }
                ?.takeIf { it <= budget }
        }.sorted()
        fun percentile(fraction: Double): Double? = if (latencies.isEmpty()) null else
            latencies[(kotlin.math.ceil(latencies.size * fraction).toInt() - 1).coerceAtLeast(0)]
        linkedMapOf("withinSeconds" to budget, "eligiblePlayers" to eligible.size,
            "reachedPlayers" to latencies.size, "withoutOutcomePlayers" to eligible.size - latencies.size,
            "medianSecondsAmongReached" to percentile(0.5), "p90SecondsAmongReached" to percentile(0.9))
    }

    private fun PlayerRecord.exitStage(): ProductExitStage = when {
        firstGameplayOutcomeAt.isNotEmpty() -> ProductExitStage.ENGAGED
        firstPathAt.isNotEmpty() -> ProductExitStage.BEFORE_OUTCOME
        firstMenuAt != null -> ProductExitStage.BEFORE_PATH
        else -> ProductExitStage.BEFORE_MENU
    }

    private fun player(
        id: String,
        now: Long,
    ): PlayerRecord {
        players[id]?.let { return it }
        while (players.size >= config.maxTrackedPlayers) {
            val oldest = players.minByOrNull { it.value.lastSeenAt }?.key ?: break
            players.remove(oldest)
            capacityEvictions++
        }
        return PlayerRecord(id, now, now).also {
            players[id] = it
            markDirty()
        }
    }

    private fun PlayerRecord.day(date: String): DailyRecord = days.getOrPut(date) { DailyRecord(date) }

    private fun PlayerRecord.journey(): ProductJourneySnapshot =
        ProductJourneySnapshot(
            firstJoinAt,
            firstMenuAt,
            firstPathAt.mapNotNull { (key, value) -> ProductPath.entries.firstOrNull { it.label == key }?.let { it to value } }.toMap(),
            firstGameplayOutcomeAt.mapNotNull { (key, value) -> ProductPath.entries.firstOrNull { it.label == key }?.let { it to value } }.toMap(),
        )

    private fun PlayerRecord.window(
        start: LocalDate,
        end: LocalDate,
    ): List<DailyRecord>? = days.values.filter { day -> parseDate(day.date)?.let { !it.isBefore(start) && !it.isAfter(end) } == true }.takeIf { it.isNotEmpty() }

    private fun MutableMap<String, Long>.putIfEarlier(
        key: String,
        value: Long,
    ): Boolean {
        val previous = this[key]
        if (previous != null && previous <= value) return false
        this[key] = value
        return true
    }

    private fun DailyRecord.recordTrail(signal: ProductSignal): Boolean {
        val terminalConnection =
            signal.detail
                ?.takeIf { signal.kind == ProductEventKind.DETAIL && it.type == ProductDetailType.CONNECTION }
                ?.key
                ?.let { key -> TERMINAL_CONNECTIONS.any { it.label == key } }
                ?: false
        if (terminalConnection) {
            val changed = recentTrail.isNotEmpty()
            recentTrail.clear()
            return changed
        }
        val raw =
            when (signal.kind) {
                ProductEventKind.MENU_OPEN -> "menu" to ProductFeature.MAIN_MENU.label
                ProductEventKind.HELP_OPEN -> "help" to ProductFeature.HELP.label
                ProductEventKind.PATH_INTEREST -> "path_interest" to signal.path.label
                ProductEventKind.PATH_CHOICE -> "path" to signal.path.label
                ProductEventKind.ACTIVITY -> signal.activity?.let { "activity" to it.label }
                ProductEventKind.FEATURE_INTEREST -> signal.feature?.let { "feature" to it.label }
                ProductEventKind.MEANINGFUL_OUTCOME -> signal.outcome?.let { "outcome" to it.label }
                ProductEventKind.DETAIL ->
                    signal.detail?.let { detail ->
                        when (detail.type) {
                            ProductDetailType.COMMAND -> "command"
                            ProductDetailType.WORLD -> "world"
                            ProductDetailType.TELEPORT_WORLD -> "teleport"
                            ProductDetailType.NPC -> "npc"
                            ProductDetailType.TELEPORT_CAUSE -> "cause"
                            ProductDetailType.SERVER -> "server"
                            ProductDetailType.SERVER_TARGET -> "server_target"
                            ProductDetailType.CONNECTION -> "connection"
                            ProductDetailType.ONBOARDING_HINT -> "onboarding_hint"
                        } to detail.key
                    }
                else -> null
            } ?: return false
        val step = ProductWireCodec.trailStep(raw.first, raw.second) ?: return false
        val candidate = TrailRecord(signal.occurredAt, step)
        if (recentTrail.lastOrNull() == candidate) return false
        recentTrail += candidate
        // Kotlin's stable sort preserves publication order for signals emitted
        // inside the same millisecond (for example server then connection).
        recentTrail.sortBy(TrailRecord::occurredAt)
        val compact = recentTrail.fold(mutableListOf<TrailRecord>()) { result, record ->
            if (result.lastOrNull()?.step != record.step) result += record
            result
        }
        recentTrail.clear()
        recentTrail += compact.takeLast(MAX_TRAIL_STEPS)
        return true
    }

    private fun DailyRecord.recordDetail(
        source: String,
        detail: ProductDetail,
        maxValues: Int,
    ): Boolean {
        val bucket = details.getOrPut(detail.type.label) { linkedMapOf() }
        val id = detailId(source, detail.key)
        val existing = bucket[id]
        if (existing != null) {
            existing.count++
            if (!detail.display.isNullOrBlank()) existing.display = detail.display
            return true
        }
        val totalValues = details.values.sumOf { it.size } + exits.size
        val safeKey = if (totalValues < maxValues) detail.key else OTHER_DETAIL
        val safeSource = if (safeKey == OTHER_DETAIL) OVERFLOW_SOURCE else source
        val safeId = detailId(safeSource, safeKey)
        val record = bucket.getOrPut(safeId) { DetailRecord(safeSource, safeKey) }
        record.count++
        if (safeKey != OTHER_DETAIL && !detail.display.isNullOrBlank()) record.display = detail.display
        return true
    }

    private fun DailyRecord.recordExit(
        source: String,
        exit: ProductExitContext,
        maxValues: Int,
    ): Boolean {
        val aggregateTrail =
            recentTrail.takeIf { exit.connection == null }.orEmpty()
                .map(TrailRecord::step)
                .takeIf { it.isNotEmpty() }
                ?: exit.trail.takeLast(MAX_TRAIL_STEPS)
        val record = ExitRecord(
            source = source,
            server = exit.server,
            world = exit.world,
            command = exit.command,
            npcId = exit.npcId,
            npcName = exit.npcName,
            feature = exit.feature?.label,
            activity = exit.activity?.label,
            stage = exit.stage.label,
            teleportCause = exit.teleportCause,
            connection = exit.connection?.label,
            trail = aggregateTrail,
        )
        var id = exitId(record)
        if (id !in exits && details.values.sumOf { it.size } + exits.size >= maxValues) {
            val overflow = ExitRecord(source = OVERFLOW_SOURCE, stage = ProductExitStage.ENGAGED.label)
            id = exitId(overflow)
            val other = exits.getOrPut(id) { overflow }
            other.count++
            return true
        }
        val existing = exits.getOrPut(id) { record }
        existing.count++
        if (!exit.npcName.isNullOrBlank()) existing.npcName = exit.npcName
        return true
    }

    private fun prune(now: Long) {
        val oldestDate = localDate(now).minusDays(config.retentionDays.toLong() - 1)
        var changed = false
        players.values.forEach { player ->
            changed = player.days.entries.removeIf { parseDate(it.key)?.isBefore(oldestDate) != false } || changed
        }
        changed = players.entries.removeIf { (_, player) -> player.days.isEmpty() && localDate(player.lastSeenAt).isBefore(oldestDate) } || changed
        if (changed) markDirty()
    }

    private fun markDirty() {
        dirty = true
        mutationVersion++
    }

    private fun save(initial: StoreFile): SaveResult {
        Files.createDirectories(path.parent)
        var state = initial
        var retained = state.players.orEmpty()
        var json = gson.toJson(state)
        val evicted = linkedMapOf<String, Long>()
        while (json.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES && retained.isNotEmpty()) {
            val byteSize = json.toByteArray(Charsets.UTF_8).size.toLong()
            val estimated = floor(retained.size * (MAX_FILE_BYTES.toDouble() / byteSize) * 0.95).toInt()
            val keep = estimated.coerceIn(0, retained.size - 1)
            val newest = retained.sortedByDescending(PersistedPlayer::lastSeenAt).take(keep)
            val keptIds = newest.mapTo(hashSetOf(), PersistedPlayer::id)
            retained.filter { it.id !in keptIds }.forEach { player -> player.id?.let { evicted[it] = player.lastSeenAt } }
            retained = newest.sortedBy(PersistedPlayer::id)
            state = state.copy(players = retained, capacityEvictions = initial.capacityEvictions + evicted.size)
            json = gson.toJson(state)
        }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Product telemetry state cannot fit inside the $MAX_FILE_BYTES-byte persistence bound"
        }
        val temp = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(temp, json, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return SaveResult(evicted)
    }

    private fun persistedSnapshot(now: Long): StoreFile =
        StoreFile(
            version = VERSION,
            savedAt = now,
            capacityEvictions = capacityEvictions,
            uiDroppedEvents = uiDroppedEvents,
            resultObservationStartedAt = resultObservationStartedAt,
            players =
                players.values.sortedBy { it.id }.map { player ->
                    PersistedPlayer(
                        id = player.id,
                        firstSeenAt = player.firstSeenAt,
                        lastSeenAt = player.lastSeenAt,
                        firstJoinAt = player.firstJoinAt,
                        firstMenuAt = player.firstMenuAt,
                        firstPathAt = player.firstPathAt.toSortedMap(),
                        firstGameplayOutcomeAt = player.firstGameplayOutcomeAt.toSortedMap(),
                        days = player.days.values.sortedBy { it.date }.map(::persist),
                        uiAttribution = player.uiAttribution?.copy(),
                    )
                },
        )

    private fun persist(day: DailyRecord): PersistedDay =
        PersistedDay(
            date = day.date,
            ui = day.ui.values.map(ProductUiRow::copyForSave),
            sessions = day.sessions,
            sessionSeconds = day.sessionSeconds,
            activeSeconds = day.activeSeconds,
            menuOpened = day.menuOpened,
            helpOpened = day.helpOpened,
            activities = day.activities.sorted(),
            features = day.features.sorted(),
            pathInterests = day.pathInterests.sorted(),
            pathChoices = day.pathChoices.sorted(),
            outcomes = day.outcomes.sorted(),
            systems = day.systems.sorted(),
            actionCounts = day.actionCounts.toSortedMap(),
            details =
                day.details.toSortedMap().mapValues { (_, bucket) ->
                    bucket.values.sortedWith(compareBy<DetailRecord> { it.source }.thenBy { it.key }).map { detail ->
                        PersistedDetail(detail.source, detail.key, detail.display, detail.count)
                    }
                },
            exits =
                day.exits.values.sortedWith(compareBy<ExitRecord> { it.source }.thenBy { it.stage }.thenBy { it.world }).map { exit ->
                    PersistedExit(
                        source = exit.source,
                        server = exit.server,
                        world = exit.world,
                        command = exit.command,
                        npcId = exit.npcId,
                        npcName = exit.npcName,
                        feature = exit.feature,
                        activity = exit.activity,
                        stage = exit.stage,
                        teleportCause = exit.teleportCause,
                        connection = exit.connection,
                        trail = exit.trail,
                        count = exit.count,
                    )
                },
            recentTrail = day.recentTrail.map { PersistedTrail(it.occurredAt, it.step) },
        )

    private fun localDate(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(config.zoneId).toLocalDate()

    private fun dayKey(timestamp: Long): String = localDate(timestamp).toString()

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun outcomePath(label: String): ProductPath? = ProductOutcome.entries.firstOrNull { it.label == label }?.path

    private fun point(
        name: String,
        description: String,
        value: Number,
        scope: String,
        vararg tags: Pair<String, String>,
    ): MetricPoint = MetricPoint(name, description, value.toDouble(), mapOf("scope" to scope) + tags)

    companion object {
        const val VERSION = 1
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1_000L
        private const val MAX_TRAIL_STEPS = 12
        private val TERMINAL_CONNECTIONS =
            setOf(
                ProductConnection.DISCONNECT_ACTIVE,
                ProductConnection.LOGIN_CONFLICT,
                ProductConnection.LOGIN_CANCELLED_USER,
                ProductConnection.LOGIN_CANCELLED_PROXY,
                ProductConnection.LOGIN_CANCELLED_EARLY,
                ProductConnection.PRE_SERVER_DISCONNECT,
            )
        private val PSEUDONYM = Regex("[a-f0-9]{64}")
        private val SOURCE = Regex("[a-z0-9_.-]{1,32}")
        private const val OTHER_DETAIL = "__other__"
        private const val OVERFLOW_SOURCE = "overflow"
        private val WINDOWS = listOf(1, 7, 28)

        fun open(
            path: Path,
            config: ProductInterestConfig,
            now: Long,
            gson: Gson = Common.prettyGson,
        ): ProductInterestStore {
            if (!Files.exists(path)) return ProductInterestStore(path, config, gson, linkedMapOf(), now)
            return runCatching {
                require(Files.size(path) <= MAX_FILE_BYTES) { "Product telemetry state exceeds $MAX_FILE_BYTES bytes" }
                val model = requireNotNull(gson.fromJson(Files.readString(path), StoreFile::class.java))
                require(model.version == VERSION) { "Unsupported product telemetry state version ${model.version}" }
                val records = linkedMapOf<String, PlayerRecord>()
                model.players.orEmpty().take(config.maxTrackedPlayers).forEach { persisted ->
                    val id = persisted.id?.takeIf(PSEUDONYM::matches) ?: return@forEach
                    val newestAllowed = now + MAX_CLOCK_SKEW_MILLIS
                    if (persisted.firstSeenAt !in 0..newestAllowed || persisted.lastSeenAt !in persisted.firstSeenAt..newestAllowed) return@forEach
                    val validTime: (Long) -> Boolean = { it in 0..newestAllowed }
                    val record =
                        PlayerRecord(
                            id = id,
                            firstSeenAt = persisted.firstSeenAt,
                            lastSeenAt = persisted.lastSeenAt,
                            firstJoinAt = persisted.firstJoinAt?.takeIf(validTime),
                            uiAttribution = persisted.uiAttribution?.takeIf { click ->
                                ProductUiCodec.ID.matches(click.surface) && ProductUiCodec.REVISION.matches(click.revision) &&
                                    ProductUiCodec.ID.matches(click.button) && now - click.occurredAt in 0..600_000
                            },
                            firstGameplayOutcomeAt = persisted.firstGameplayOutcomeAt.orEmpty()
                                .filter { (key, value) -> ProductPath.entries.any { it.label == key } && validTime(value) }
                                .toMutableMap(),
                            firstMenuAt = persisted.firstMenuAt?.takeIf(validTime),
                            firstPathAt =
                                persisted.firstPathAt.orEmpty()
                                    .filter { (key, value) -> ProductPath.entries.any { it.label == key } && validTime(value) }
                                    .toMutableMap(),
                        )
                    val today = Instant.ofEpochMilli(now).atZone(config.zoneId).toLocalDate()
                    val oldest = today.minusDays(config.retentionDays.toLong() - 1)
                    persisted.days.orEmpty()
                        .asSequence()
                        .mapNotNull { restore(it, config.maxDetailValuesPerPlayerDay, config.zoneId) }
                        .filter { day -> LocalDate.parse(day.date).let { !it.isBefore(oldest) && !it.isAfter(today) } }
                        .distinctBy(DailyRecord::date)
                        .take(config.retentionDays)
                        .forEach { day -> record.days[day.date] = day }
                    records[id] = record
                }
                ProductInterestStore(path, config, gson, records, model.savedAt.coerceAtMost(now)).also {
                    it.capacityEvictions = model.capacityEvictions.coerceAtLeast(0)
                    it.uiDroppedEvents = model.uiDroppedEvents.coerceAtLeast(0)
                    it.resultObservationStartedAt = model.resultObservationStartedAt?.takeIf { at -> at in 0..now } ?: now
                    // Save the new observation boundary even before the first new player arrives.
                    if (model.resultObservationStartedAt == null) it.markDirty()
                    it.prune(now)
                }
            }.getOrElse {
                val invalid = path.resolveSibling("${path.fileName}.invalid-$now")
                Files.move(path, invalid, StandardCopyOption.REPLACE_EXISTING)
                ProductInterestStore(path, config, gson, linkedMapOf(), now, invalid)
            }
        }

        private fun restore(
            day: PersistedDay,
            maxDetailValues: Int,
            zoneId: java.time.ZoneId,
        ): DailyRecord? {
            val date = day.date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: return null
            if (day.sessions < 0 || day.sessionSeconds < 0 || day.activeSeconds !in 0..day.sessionSeconds) return null
            val details = linkedMapOf<String, MutableMap<String, DetailRecord>>()
            val maxStoredRows = maxDetailValues + ProductDetailType.entries.size + 1
            var directRows = 0
            val overflowDimensions = linkedSetOf<String>()
            day.details.orEmpty().forEach { (typeLabel, values) ->
                val type = ProductDetailType.entries.firstOrNull { it.label == typeLabel } ?: return@forEach
                val bucket = linkedMapOf<String, DetailRecord>()
                values.take(maxStoredRows).forEach { persisted ->
                    val source = persisted.source?.takeIf(SOURCE::matches) ?: return@forEach
                    val key = persisted.key?.takeIf { it == OTHER_DETAIL || ProductWireCodec.isValidDetailKey(type, it) } ?: return@forEach
                    if (persisted.count <= 0) return@forEach
                    if (key == OTHER_DETAIL) {
                        if (source != OVERFLOW_SOURCE || !overflowDimensions.add(typeLabel)) return@forEach
                    } else if (directRows >= maxDetailValues) {
                        return@forEach
                    } else {
                        directRows++
                    }
                    val id = detailId(source, key)
                    bucket[id] = DetailRecord(source, key, ProductWireCodec.sanitizeDisplay(persisted.display), persisted.count.coerceAtMost(1_000_000))
                }
                if (bucket.isNotEmpty()) details[typeLabel] = bucket
            }
            val exits = linkedMapOf<String, ExitRecord>()
            day.exits.orEmpty().take(maxStoredRows).forEach { persisted ->
                val source = persisted.source?.takeIf(SOURCE::matches) ?: return@forEach
                val stage = ProductExitStage.entries.firstOrNull { it.label == persisted.stage } ?: return@forEach
                val server = persisted.server?.takeIf { ProductWireCodec.isValidDetailKey(ProductDetailType.SERVER, it) } ?: persisted.server?.let { return@forEach }
                val world = persisted.world?.takeIf { ProductWireCodec.isValidDetailKey(ProductDetailType.WORLD, it) } ?: persisted.world?.let { return@forEach }
                val command = persisted.command?.takeIf { ProductWireCodec.isValidDetailKey(ProductDetailType.COMMAND, it) } ?: persisted.command?.let { return@forEach }
                val npcId = persisted.npcId?.takeIf { ProductWireCodec.isValidDetailKey(ProductDetailType.NPC, it) } ?: persisted.npcId?.let { return@forEach }
                val teleportCause =
                    persisted.teleportCause?.takeIf {
                        ProductWireCodec.isValidDetailKey(ProductDetailType.TELEPORT_CAUSE, it)
                    } ?: persisted.teleportCause?.let { return@forEach }
                val connection = persisted.connection?.takeIf { ProductWireCodec.isValidDetailKey(ProductDetailType.CONNECTION, it) } ?: persisted.connection?.let { return@forEach }
                if (persisted.count <= 0) return@forEach
                val trail = persisted.trail.orEmpty().mapNotNull { step -> ProductWireCodec.trailStep(step.substringBefore('='), step.substringAfter('=', "")) }.take(MAX_TRAIL_STEPS)
                val overflow =
                    source == OVERFLOW_SOURCE &&
                        stage == ProductExitStage.ENGAGED &&
                        listOf(server, world, command, npcId, teleportCause, connection, persisted.feature, persisted.activity, persisted.npcName).all { it == null } &&
                        trail.isEmpty()
                if (overflow) {
                    if (!overflowDimensions.add("exit")) return@forEach
                } else if (directRows >= maxDetailValues) {
                    return@forEach
                } else {
                    directRows++
                }
                val record = ExitRecord(
                    source = source,
                    server = server,
                    world = world,
                    command = command,
                    npcId = npcId,
                    npcName = ProductWireCodec.sanitizeDisplay(persisted.npcName),
                    feature = persisted.feature?.takeIf { value -> ProductFeature.entries.any { it.label == value } },
                    activity = persisted.activity?.takeIf { value -> ProductActivity.entries.any { it.label == value } },
                    stage = stage.label,
                    teleportCause = teleportCause,
                    connection = connection,
                    trail = trail,
                    count = persisted.count.coerceAtMost(1_000_000),
                )
                exits[exitId(record)] = record
            }
            val recentTrail =
                day.recentTrail.orEmpty()
                    .mapNotNull { persisted ->
                        val step = persisted.step ?: return@mapNotNull null
                        val normalized = ProductWireCodec.trailStep(step.substringBefore('='), step.substringAfter('=', "")) ?: return@mapNotNull null
                        if (persisted.occurredAt <= 0 || Instant.ofEpochMilli(persisted.occurredAt).atZone(zoneId).toLocalDate().toString() != date) {
                            return@mapNotNull null
                        }
                        TrailRecord(persisted.occurredAt, normalized)
                    }
                    .sortedBy(TrailRecord::occurredAt)
                    .takeLast(MAX_TRAIL_STEPS)
                    .toMutableList()
            return DailyRecord(
                date = date,
                ui = day.ui.orEmpty().filter { runCatching { it.valid() }.getOrDefault(false) }.take(128).associateBy { it.key }.toMutableMap(),
                sessions = day.sessions,
                sessionSeconds = day.sessionSeconds,
                activeSeconds = day.activeSeconds,
                menuOpened = day.menuOpened,
                helpOpened = day.helpOpened,
                activities = day.activities.orEmpty().filter { value -> ProductActivity.entries.any { it.label == value } }.toMutableSet(),
                features = day.features.orEmpty().filter { value -> ProductFeature.entries.any { it.label == value } }.toMutableSet(),
                pathInterests = day.pathInterests.orEmpty().filter { value -> ProductPath.entries.any { it.label == value } }.toMutableSet(),
                pathChoices = day.pathChoices.orEmpty().filter { value -> ProductPath.entries.any { it.label == value } }.toMutableSet(),
                outcomes = day.outcomes.orEmpty().filter { value -> ProductOutcome.entries.any { it.label == value && it.isGameplayResult() } }.toMutableSet(),
                systems =
                    day.systems.orEmpty()
                        .filter { value -> ProductFeature.entries.any { it.countsAsSystem && it.label == value } }
                        .take(ProductFeature.entries.size)
                        .toMutableSet(),
                actionCounts =
                    day.actionCounts.orEmpty()
                        .filterKeys { value -> ProductAction.entries.any { it.label == value } }
                        .mapValues { it.value.coerceIn(0, 1_000_000) }
                        .toMutableMap(),
                details = details,
                exits = exits,
                recentTrail = recentTrail,
            )
        }

        private fun detailId(
            source: String,
            key: String,
        ): String = "$source\u001f$key"

        private fun exitId(exit: ExitRecord): String =
            listOf(
                exit.source,
                exit.server.orEmpty(),
                exit.world.orEmpty(),
                exit.command.orEmpty(),
                exit.npcId.orEmpty(),
                exit.feature.orEmpty(),
                exit.activity.orEmpty(),
                exit.stage,
                exit.teleportCause.orEmpty(),
                exit.connection.orEmpty(),
                exit.trail.joinToString(">"),
            ).joinToString("\u001f")
    }
}
