package ru.arc.metrics.telemetry

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.audit.AuditConfig
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.metrics.ProductInterestConfig
import ru.arc.metrics.ProductUiKind
import ru.arc.metrics.ProductUiView
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetrySettings
import ru.arc.telemetry.PlayerTelemetryStore
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private val contexts = ConcurrentHashMap<UUID, JourneyContext>()
    private val observedActions = ConcurrentHashMap<UUID, Pair<String, Long>>()
    private var tracker: PlayerJourneyTracker? = null
    private var listener: PlayerTelemetryListener? = null
    private var sampler: ScheduledTask? = null
    private var qaNames: Set<String> = emptySet()

    fun storeOrNull(): PlayerTelemetryStore? = store
    fun health(): Map<String, Any?> = mapOf(
        "state" to state, "server" to (ARC.serverName ?: "unknown"),
        "storage" to store?.health()?.asMap(), "trackedSessions" to contexts.size, "invalidCaptures" to invalidCaptures.get(),
        "sessionScope" to "paper_backend_visit", "positionSampling" to "interval_displacement",
        "captureDurability" to "asynchronous_after_local_journal_commit",
    )

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        val epoch = generation.incrementAndGet()
        val config = ConfigManager.ofModule(ARC.instance.dataPath, "player-telemetry.yml")
        if (!config.bool("enabled", true)) { state = "disabled"; return }
        val sql = runCatching { AuditConfig.load().mysql?.copy(minimumIdle = 1, maximumPoolSize = 2) }.getOrNull()
        if (sql == null) {
            state = "missing_sql_configuration"
            warn("Player telemetry requires the existing audit MySQL configuration")
            return
        }
        qaNames = ProductInterestConfig.from(ConfigManager.ofModule(ARC.instance.dataPath, "metrics.yml")).qaPlayerNames
        val instance = PlayerTelemetryStore(sql, ARC.instance.dataPath.resolve("data"),
            ARC.serverName ?: "unknown", PlayerTelemetrySettings.from(config))
        store = instance
        state = "starting"
        instance.start().whenComplete { _, failure ->
            if (generation.get() != epoch || store !== instance) return@whenComplete
            if (failure != null) {
                state = "journal_unavailable"
                warn("Player telemetry local recovery failed: {}", failure.javaClass.simpleName)
            } else Tasks.scheduler.runSync(Runnable {
                if (generation.get() != epoch || store !== instance) return@Runnable
                tracker = PlayerJourneyTracker(
                    config.long("idle-after-seconds", 60).coerceIn(10, 3_600) * 1_000,
                    config.long("position-interval-seconds", 15).coerceIn(5, 300) * 1_000,
                    { context, event, at, attrs -> capture(context, "player", event, null, null, attrs, at) },
                    { id, context -> if (context == null) contexts.remove(id) else contexts[id] = context },
                )
                listener = PlayerTelemetryListener().also { it.start() }
                ru.arc.metrics.MetricsModule.ensureUiCapture()
                Bukkit.getOnlinePlayers().forEach { join(it, true) }
                sampler = Tasks.scheduler.runTimer(20, config.long("sample-interval-seconds", 1).coerceIn(1, 10) * 20, Runnable {
                    val now = System.currentTimeMillis()
                    observedActions.entries.toList().forEach { (id, action) ->
                        if (observedActions.remove(id, action)) tracker?.activity(id, action.first, action.second)
                    }
                    Bukkit.getOnlinePlayers().forEach { tracker?.sample(it.uniqueId, it.location.position(), now) }
                })
                state = "capturing"
                instance.offer(PlayerTelemetryEvent(eventId = UUID.randomUUID().toString(), occurredAt = System.currentTimeMillis(),
                    server = ARC.serverName ?: "unknown", source = "arc", event = "node.started",
                    attributes = mapOf("version" to ARC.instance.description.version)))
            })
        }
    }

    fun metricSnapshot(): List<ru.arc.metrics.core.MetricPoint> {
        val health = store?.health()
        fun point(suffix: String, help: String, value: Number) =
            ru.arc.metrics.core.MetricPoint("arc_player_telemetry_$suffix", help, value.toDouble())
        return listOf(
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

    override fun reload() {
        val previous = stopCapture("reload")
        val epoch = generation.get()
        val completion = previous?.closeAsync() ?: closing ?: CompletableFuture.completedFuture(Unit)
        closing = completion
        completion.whenComplete { _, failure ->
            if (generation.get() == epoch) {
                if (failure == null) Tasks.scheduler.runSync(Runnable { if (generation.get() == epoch) init() })
                else {
                    state = "shutdown_drain_failed"
                    warn("Player telemetry reload stopped because its previous journal did not close cleanly")
                }
            }
        }
    }

    override fun shutdown() {
        val previous = stopCapture("shutdown")
        closing = previous?.closeAsync() ?: closing
        // Only shutdown waits; gameplay and reload never wait for disk or network.
        runCatching { closing?.get(10, TimeUnit.SECONDS) }.onFailure {
            warn("Player telemetry shutdown did not finish within its bound: {}", it.javaClass.simpleName)
        }
    }

    private fun stopCapture(reason: String): PlayerTelemetryStore? {
        generation.incrementAndGet()
        sampler?.cancel(); sampler = null
        listener?.close(); listener = null
        tracker?.close(System.currentTimeMillis(), reason); tracker = null
        observedActions.clear()
        val previous = store
        store = null
        state = "stopped"
        return previous
    }

    internal fun join(player: Player, resumed: Boolean = false) = tracker?.join(player.uniqueId, player.name,
        player.name.lowercase() in qaNames, player.location.position(), System.currentTimeMillis(), resumed)

    internal fun leave(player: Player) {
        observedActions.remove(player.uniqueId)?.let { tracker?.activity(player.uniqueId, it.first, it.second) }
        tracker?.relocate(player.uniqueId, player.location.position(), System.currentTimeMillis())
        tracker?.leave(player.uniqueId, System.currentTimeMillis(), "quit", false)
    }

    internal fun relocate(player: Player, location: Location) = tracker?.relocate(player.uniqueId, location.position(), System.currentTimeMillis())

    /** Optional cross-plugin ingress: no live Bukkit access, blocking operations or exceptions escape to gameplay. */
    fun record(playerId: UUID, source: String, event: String, subject: String? = null,
               operationId: String? = null, attributes: Map<String, String> = emptyMap()): Boolean {
        val context = contexts[playerId]
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
        markActivity(player.uniqueId, event, System.currentTimeMillis())
        return record(player.uniqueId, "player", event, subject, null, attributes)
    }

    internal fun ui(playerId: String, visit: String, kind: ProductUiKind, view: ProductUiView, button: String, duration: Long, at: Long) {
        val player = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return
        if (kind in setOf(ProductUiKind.CLICK, ProductUiKind.ATTEMPT, ProductUiKind.BLOCKED))
            markActivity(player, "ui.${kind.name.lowercase()}", at)
        val details = view.details + mapOf("visitId" to visit, "button" to button, "revision" to view.revision,
            "durationMs" to duration.toString()) + (view.buttons[button]?.let { mapOf("slot" to it.slot.toString()) } ?: emptyMap())
        capture(contexts[player], "ui", "ui.${kind.name.lowercase()}", view.surface, null, details, at, player)
    }

    private fun markActivity(player: UUID, event: String, at: Long) {
        if (Bukkit.isPrimaryThread()) tracker?.activity(player, event, at)
        else observedActions[player] = event to at
    }

    private fun capture(context: JourneyContext?, source: String, event: String, subject: String?, operationId: String?,
                        attributes: Map<String, String>, at: Long, playerId: UUID? = context?.playerId,
                        playerName: String? = context?.playerName): Boolean {
        val instance = store ?: return false
        return runCatching {
            val id = UUID.randomUUID().toString()
            instance.offer(PlayerTelemetryEvent(eventId = id, occurredAt = at, server = ARC.serverName ?: "unknown",
                playerId = playerId?.toString(), playerName = playerName, sessionId = context?.sessionId,
                source = source, event = event, subject = subject, operationId = operationId,
                world = context?.position?.world, x = context?.position?.x, y = context?.position?.y, z = context?.position?.z,
                qa = context?.qa ?: (playerName?.lowercase() in qaNames), attributes = attributes.toMap()))
        }.onFailure {
            invalidCaptures.incrementAndGet()
            instance.noteCaptureLoss()
        }.getOrDefault(false)
    }

    internal fun Location.position() = JourneyPosition(world.name, x, y, z)
}
