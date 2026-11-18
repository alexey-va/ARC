package ru.arc.metrics.telemetry

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.audit.AuditConfig
import ru.arc.config.Config
import ru.arc.metrics.loadTelemetryConfig
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.MetricsWorkQueue
import ru.arc.metrics.NativeTelemetryBatch
import ru.arc.metrics.ProductInterestConfig
import ru.arc.metrics.ProductMenuEvent
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetrySettings
import ru.arc.telemetry.PlayerTelemetryStore
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** Captures immutable values on Paper; serialization, fsync, SQL and retries belong to the shared background store. */
object PlayerTelemetryModule : PluginModule {
    override val name = "PlayerTelemetry"
    override val priority = 35
    @Volatile private var store: PlayerTelemetryStore? = null
    @Volatile private var state = "disabled"
    private val generation = AtomicLong()
    @Volatile private var closing: CompletableFuture<Unit>? = null
    private val invalidCaptures = AtomicLong()
    private val unavailableCaptures = AtomicLong()
    private class Lifecycle(val queue: MetricsWorkQueue) { var store: PlayerTelemetryStore? = null }
    private var lifecycle: Lifecycle? = null
    private val contexts = ConcurrentHashMap<UUID, JourneyContext>()
    private val observedActions = ConcurrentHashMap<UUID, Pair<String, Long>>()
    private var tracker: PlayerJourneyTracker? = null
    private var listener: PlayerTelemetryListener? = null
    private var sampler: ScheduledTask? = null
    private var actionBuffer: PlayerTelemetryActionBuffer? = null
    private var actionFlush: ScheduledTask? = null
    private var nativeCapture: NativeTelemetryBatch? = null
    private var qaNames: Set<String> = emptySet()

    fun storeOrNull(): PlayerTelemetryStore? = store
    fun health(): Map<String, Any?> = mapOf(
        "state" to state, "server" to (ARC.serverName ?: "unknown"),
        "storage" to store?.health()?.asMap(), "trackedSessions" to contexts.size, "invalidCaptures" to invalidCaptures.get(),
        "unavailableCaptures" to unavailableCaptures.get(), "sessionScope" to "paper_backend_visit", "positionSampling" to "interval_displacement",
        "captureDurability" to "asynchronous_after_local_journal_commit",
    )

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        stopCapture("reload")
        val epoch = generation.incrementAndGet()
        val dataPath = ARC.instance.dataPath
        val serverName = ARC.serverName ?: "unknown"
        val version = ARC.instance.pluginMeta.version
        state = "starting"
        val queue = MetricsWorkQueue(predecessor = closing ?: CompletableFuture.completedFuture(Unit),
            onFailure = { warn("Player telemetry asynchronous processing failed", it) })
        val owner = Lifecycle(queue).also { lifecycle = it }
        queue.submit {
            val config = loadTelemetryConfig(dataPath, "player-telemetry.yml")
            if (!config.bool("enabled", true)) {
                if (generation.get() == epoch) state = "disabled"
                return@submit
            }
            val sql = AuditConfig(Config(dataPath, "modules/audit.yml")).mysql?.copy(minimumIdle = 1, maximumPoolSize = 2)
            if (sql == null) {
                if (generation.get() == epoch) state = "missing_sql_configuration"
                warn("Player telemetry requires the existing audit MySQL configuration")
                return@submit
            }
            val capturedQaNames = ProductInterestConfig.from(loadTelemetryConfig(dataPath, "metrics.yml")).qaPlayerNames
            val idleMillis = config.long("idle-after-seconds", 60).coerceIn(10, 3_600) * 1_000
            val positionMillis = config.long("position-interval-seconds", 15).coerceIn(5, 300) * 1_000
            val sampleTicks = config.long("sample-interval-seconds", 1).coerceIn(1, 10) * 20
            val instance = PlayerTelemetryStore(sql, dataPath.resolve("data"), serverName, PlayerTelemetrySettings.from(config))
            owner.store = instance
            instance.start().join() // this queue's worker, never Paper
            Tasks.scheduler.runSync(Runnable {
                if (generation.get() != epoch || lifecycle !== owner) return@Runnable
                store = instance
                qaNames = capturedQaNames
                val actions = PlayerTelemetryActionBuffer(serverName, pendingSince = instance::setBufferedCaptureSince) { snapshot ->
                    offer(instance, snapshot)
                }
                actionBuffer = actions
                actionFlush = Tasks.scheduler.runTimer(20L, 20L, Runnable {
                    if (actionBuffer === actions) actions.flushExpired(System.currentTimeMillis())
                })
                tracker = PlayerJourneyTracker(
                    idleMillis,
                    positionMillis,
                    { context, event, at, attrs -> capture(context, "player", event, null, null, attrs, at, targetStore = instance) },
                    { id, context -> if (context == null) contexts.remove(id) else contexts[id] = context },
                )
                listener = PlayerTelemetryListener().also { it.start() }
                MetricsModule.ensureUiCapture()
                val batch = NativeTelemetryBatch().also { nativeCapture = it }
                batch.start(Bukkit.getOnlinePlayers().map { it.uniqueId }) { id ->
                    if (!contexts.containsKey(id)) Bukkit.getPlayer(id)?.let { join(it, true) }
                }
                sampler = Tasks.scheduler.runTimer(20, sampleTicks, Runnable {
                    batch.start((Bukkit.getOnlinePlayers().map { it.uniqueId } + observedActions.keys).distinct()) { id ->
                        observedActions.remove(id)?.let { tracker?.activity(id, it.first, it.second) }
                        val player = Bukkit.getPlayer(id)
                        if (player != null && contexts.containsKey(id))
                            tracker?.sample(id, player.location.position(), System.currentTimeMillis())
                    }
                })
                state = "capturing"
                instance.offer(PlayerTelemetryEvent(eventId = UUID.randomUUID().toString(), occurredAt = System.currentTimeMillis(),
                    server = serverName, source = "arc", event = "node.started",
                    attributes = mapOf("version" to version)))
            })
        }.whenComplete { _, failure ->
            if (failure != null && generation.get() == epoch) state = "journal_unavailable"
        }
    }

    fun metricSnapshot(): List<ru.arc.metrics.core.MetricPoint> {
        val health = store?.health()
        fun point(suffix: String, help: String, value: Number) =
            ru.arc.metrics.core.MetricPoint("arc_player_telemetry_$suffix", help, value.toDouble())
        return listOf(
            point("unavailable_captures_total", "Capture calls rejected while the local journal was unavailable", unavailableCaptures.get()),
            point("accepting", "Player telemetry capture accepts events", if (health?.accepting == true) 1 else 0),
            point("sql_ready", "Player telemetry SQL sender is ready", if (health?.sqlReady == true) 1 else 0),
            point("queued_events", "Player telemetry events awaiting local journal commit", health?.queuedEvents ?: 0),
            point("durable_events", "Player telemetry journal events awaiting SQL acknowledgement", health?.durableEvents ?: 0),
            point("durable_bytes", "Player telemetry local journal bytes", health?.durableBytes ?: 0),
            point("delivered_events_since_start", "Player telemetry SQL acknowledgements since process start", health?.deliveredEventsSinceStart ?: 0),
            point("retries_since_start", "Player telemetry delivery retries since process start", health?.retriesSinceStart ?: 0),
            point("dropped_events_since_start", "Player telemetry capture losses since process start", health?.droppedEventsSinceStart ?: 0),
            point("corrupt_records", "Player telemetry quarantined journal records", health?.corruptRecords ?: 0),
            point("saturated", "Player telemetry queue or journal is saturated", if (health?.saturated == true) 1 else 0),
            point("coverage_gap", "Player telemetry has a persisted possible or known coverage gap", if (health?.coverageGapFrom != null) 1 else 0),
            point("oldest_outbox_age_seconds", "Age of oldest pending player telemetry event", health?.oldestOutboxAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 1000.0) } ?: 0),
        )
    }

    override fun reload() = init()

    override fun shutdown() { stopCapture("shutdown") }

    private fun stopCapture(reason: String): CompletableFuture<Unit> {
        generation.incrementAndGet()
        sampler?.cancel(); sampler = null
        actionFlush?.cancel(); actionFlush = null
        listener?.close(); listener = null
        nativeCapture?.close(); nativeCapture = null
        actionBuffer?.flushAll()
        actionBuffer = null
        // MetricsModule owns the listener and shuts down after the telemetry store.
        if (store != null) MetricsModule.finishUiVisits(reason)
        val previousTracker = tracker
        tracker = null
        val finalActions = observedActions.toMap()
        observedActions.clear()
        val owner = lifecycle
        lifecycle = null
        store = null
        state = "stopped"
        val at = System.currentTimeMillis()
        if (owner == null) return closing ?: CompletableFuture.completedFuture(Unit)
        // The published journal drains asynchronously. Our non-daemon owner keeps that
        // final drain alive and writes the censored session summaries without a Paper wait.
        return owner.queue.closeAsync {
            finalActions.forEach { (id, action) -> previousTracker?.activity(id, action.first, action.second) }
            try { previousTracker?.close(at, reason) }
            finally { owner.store?.closeAsync()?.join() }
        }.also { closing = it }
    }

    internal fun join(player: Player, resumed: Boolean = false) {
        if (player.hasMetadata("NPC")) return
        val playerId = player.uniqueId
        actionBuffer?.flushPlayer(playerId)
        tracker?.join(playerId, player.name,
            player.name.lowercase() in qaNames, player.location.position(), System.currentTimeMillis(), resumed)
    }

    internal fun leave(player: Player) {
        val playerId = player.uniqueId
        actionBuffer?.flushPlayer(playerId)
        observedActions.remove(playerId)?.let { tracker?.activity(playerId, it.first, it.second) }
        tracker?.relocate(playerId, player.location.position(), System.currentTimeMillis())
        tracker?.leave(playerId, System.currentTimeMillis(), "quit", false)
    }

    internal fun relocate(player: Player, location: Location) {
        val playerId = player.uniqueId
        actionBuffer?.flushPlayer(playerId)
        tracker?.relocate(playerId, location.position(), System.currentTimeMillis())
    }

    /** Optional cross-plugin ingress: no live Bukkit access, blocking operations or exceptions escape to gameplay. */
    fun record(playerId: UUID, source: String, event: String, subject: String? = null,
               operationId: String? = null, attributes: Map<String, String> = emptyMap()): Boolean {
        val context = contexts[playerId]
        return capture(context, source, event, subject, operationId, attributes, System.currentTimeMillis(), playerId)
    }

    /** Native Paper callbacks are accepted only for a player with a live, tracked join context. */
    internal fun recordNative(playerId: UUID, source: String, event: String, subject: String? = null,
                              operationId: String? = null, attributes: Map<String, String> = emptyMap()): Boolean {
        val context = contexts[playerId] ?: return false
        return capture(context, source, event, subject, operationId, attributes, System.currentTimeMillis(), playerId)
    }

    /** A pointer/snapshot of the financial observation, not a second financial ledger or an extra money mutation. */
    internal fun economy(observation: ru.arc.audit.AuditEvent) {
        if (store == null) return
        val transaction = observation.transaction
        val evidence = transaction.context
        val id = evidence?.accountId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val context = id?.let(contexts::get)
        val attributes = buildMap {
            put("ledgerEventId", observation.eventId)
            put("currency", transaction.normalizedCurrency)
            put("amount", java.math.BigDecimal.valueOf(transaction.amount).toPlainString())
            put("flow", transaction.normalizedFlow.label)
            put("recordKind", evidence?.normalizedRecordKind?.name ?: "TRANSACTION")
            put("status", evidence?.normalizedStatus?.name ?: "SUCCEEDED")
            evidence?.action?.let { put("action", it) }
            evidence?.correlationId?.let { put("correlationId", it) }
            evidence?.balanceBefore?.takeIf { it.isFinite() }?.let { put("balanceBefore", java.math.BigDecimal.valueOf(it).toPlainString()) }
            evidence?.balanceAfter?.takeIf { it.isFinite() }?.let { put("balanceAfter", java.math.BigDecimal.valueOf(it).toPlainString()) }
        }
        capture(context, "economy", "economy.observed", transaction.normalizedSource.label, observation.eventId,
            attributes, transaction.timestamp, id, observation.playerName)
    }

    internal fun action(player: Player, event: String, subject: String? = null, attributes: Map<String, String> = emptyMap()): Boolean {
        val playerId = player.uniqueId
        val context = contexts[playerId] ?: return false
        val at = System.currentTimeMillis()
        markActivity(playerId, event, at)
        val buffer = actionBuffer
        if (buffer != null && event in PlayerTelemetryActionBuffer.EVENTS && Bukkit.isPrimaryThread()) {
            buffer.add(context, player.world.name, event, subject, attributes, at)
            return true
        }
        return capture(context, "player", event, subject, null, attributes, at, playerId)
    }

    internal fun menu(observation: ProductMenuEvent) {
        val player = runCatching { UUID.fromString(observation.player) }.getOrNull() ?: return
        val context = contexts[player] ?: return
        if (observation.event == "menu.action") {
            val activity = when (observation.details["admission"]) {
                "accepted" -> "ui.click"
                "blocked" -> "ui.blocked"
                else -> "ui.attempt"
            }
            markActivity(player, activity, observation.at)
        }
        capture(context, "menu", observation.event, observation.surface, observation.actionId,
            observation.details + ("visitId" to observation.visit), observation.at, player)
    }

    private fun markActivity(player: UUID, event: String, at: Long) {
        if (Bukkit.isPrimaryThread()) tracker?.activity(player, event, at)
        else observedActions[player] = event to at
    }

    private fun capture(context: JourneyContext?, source: String, event: String, subject: String?, operationId: String?,
                        attributes: Map<String, String>, at: Long, playerId: UUID? = context?.playerId,
                        playerName: String? = context?.playerName, targetStore: PlayerTelemetryStore? = store): Boolean {
        val instance = targetStore ?: run { unavailableCaptures.incrementAndGet(); return false }
        return offer(instance) {
            val id = UUID.randomUUID().toString()
            val position = context?.position?.takeUnless { source == "menu" }
            PlayerTelemetryEvent(eventId = id, occurredAt = at, server = ARC.serverName ?: "unknown",
                playerId = playerId?.toString(), playerName = playerName, sessionId = context?.sessionId,
                source = source, event = event, subject = subject, operationId = operationId,
                world = position?.world, x = position?.x, y = position?.y, z = position?.z,
                qa = context?.qa ?: (playerName?.lowercase() in qaNames), attributes = attributes.toMap())
        }
    }

    private inline fun offer(instance: PlayerTelemetryStore, snapshot: () -> PlayerTelemetryEvent): Boolean =
        runCatching { instance.offer(snapshot()) }.onFailure {
            invalidCaptures.incrementAndGet()
            instance.noteCaptureLoss()
        }.getOrDefault(false)

    internal fun Location.position() = JourneyPosition(world.name, x, y, z)
}
