package ru.arc.metrics

import com.google.gson.Gson
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.arc.metrics.core.MetricPoint
import ru.arc.product.ProductOnboardingHint
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.util.Common
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class ProductPlayerSample(
    val playerId: String,
    val world: ProductWorldType,
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * Product-interest telemetry with fixed labels and privacy-safe rolling state.
 *
 * High-volume actions remain local counters. Only the first bounded activity,
 * feature, path, outcome, and session summary is published to the dedicated
 * Redis channel so the primary ARC node can expose network-level unique gauges.
 */
class ProductInterestTelemetry(
    private val registry: MeterRegistry,
    private val config: ProductInterestConfig,
    rawServerName: String,
    statePath: Path,
    private val primaryAggregator: Boolean,
    private val redis: RedisOperations? = null,
    private val gson: Gson = Common.gson,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Session(
        val player: String,
        val cohort: ProductCohort,
        val qa: Boolean,
        val startedAt: Long,
        var lastSampleAt: Long,
        var lastActiveAt: Long,
        var activeSeconds: Long = 0,
        var actionCount: Long = 0,
        var lastWorld: ProductWorldType? = null,
        var lastWorldName: String? = null,
        var lastCommand: String? = null,
        var lastNpcId: String? = null,
        var lastNpcName: String? = null,
        var lastFeature: ProductFeature? = null,
        var lastActivity: ProductActivity? = null,
        var lastTeleportCause: String? = null,
        var lastX: Double? = null,
        var lastY: Double? = null,
        var lastZ: Double? = null,
        val systems: MutableSet<String> = linkedSetOf(),
        val trail: ArrayDeque<String> = ArrayDeque(),
    )

    private data class EventMeterKey(val kind: ProductEventKind, val path: ProductPath)
    private data class FeatureMeterKey(val feature: ProductFeature, val entry: ProductEntryPoint)
    private data class LatencyMeterKey(val transition: String, val path: ProductPath)
    private data class PlayerTimeMeterKey(val state: String, val cohort: ProductCohort)
    private data class TransitionMeterKey(val from: ProductWorldType, val to: ProductWorldType)
    private data class TeleportMeterKey(val cause: ProductTeleportType, val from: ProductWorldType, val to: ProductWorldType)

    private val serverName = normalizeServer(rawServerName)
    private val store = ProductInterestStore.open(statePath, config, clockMillis(), gson)
    private val sessions = linkedMapOf<String, Session>()
    private val seenEvents =
        object : LinkedHashMap<String, Unit>(MAX_SEEN_EVENTS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > MAX_SEEN_EVENTS
        }

    private val eventCounters =
        ProductEventKind.entries.flatMap { kind ->
            ProductPath.entries.map { path ->
                EventMeterKey(kind, path) to
                    Counter
                        .builder("arc_product_events")
                        .description("Observed bounded product telemetry events")
                        .tags("event", kind.label, "path", path.label)
                        .register(registry)
            }
        }.toMap()
    private val featureCounters =
        ProductFeature.entries.flatMap { feature ->
            ProductEntryPoint.entries.map { entry ->
                FeatureMeterKey(feature, entry) to
                    Counter
                        .builder("arc_product_feature_interest")
                        .description("Observed interest in bounded product features")
                        .tags("feature", feature.label, "entry_point", entry.label, "path", feature.path.label)
                        .register(registry)
            }
        }.toMap()
    private val actionCounters =
        ProductAction.entries.associateWith { action ->
            Counter
                .builder("arc_product_actions")
                .description("Observed gameplay action volume by bounded activity")
                .tags("action", action.label, "activity", action.activity.label)
                .register(registry)
        }
    private val outcomeCounters =
        ProductOutcome.entries.associateWith { outcome ->
            Counter
                .builder("arc_product_meaningful_outcomes")
                .description("Observed meaningful player outcomes")
                .tags("outcome", outcome.label, "activity", outcome.activity.label, "path", outcome.path.label)
                .register(registry)
        }
    private val transportCounters =
        listOf("local", "publish_attempt", "publish_unavailable", "receive", "duplicate", "invalid").associateWith { result ->
            Counter
                .builder("arc_product_telemetry_transport")
                .description("Product telemetry transport observations")
                .tag("result", result)
                .register(registry)
        }
    private val sessionDuration =
        ProductCohort.entries.associateWith { cohort ->
            Timer
                .builder("arc_product_session_duration")
                .description("Organic completed product session duration")
                .tag("cohort", cohort.label)
                .register(registry)
        }
    private val sessionActiveDuration =
        ProductCohort.entries.associateWith { cohort ->
            Timer
                .builder("arc_product_session_active_duration")
                .description("Observed active time in organic completed sessions")
                .tag("cohort", cohort.label)
                .register(registry)
        }
    private val sessionSystems =
        ProductCohort.entries.associateWith { cohort ->
            DistributionSummary
                .builder("arc_product_session_systems")
                .description("Distinct bounded product systems used per completed session")
                .tag("cohort", cohort.label)
                .register(registry)
        }
    private val sessionActions =
        ProductCohort.entries.associateWith { cohort ->
            DistributionSummary
                .builder("arc_product_session_actions")
                .description("Observed gameplay actions per completed session")
                .tag("cohort", cohort.label)
                .register(registry)
        }
    private val latencyTimers =
        buildList {
            add(LatencyMeterKey("join_to_menu", ProductPath.NONE))
            ProductPath.entries.filterNot { it == ProductPath.NONE }.forEach { path ->
                add(LatencyMeterKey("menu_to_path", path))
                add(LatencyMeterKey("path_to_outcome", path))
            }
        }.associateWith { key ->
            Timer
                .builder("arc_product_funnel_latency")
                .description("Latency between bounded new-player funnel stages")
                .tags("transition", key.transition, "path", key.path.label)
                .register(registry)
        }
    private val playerTimeCounters =
        ProductCohort.entries.flatMap { cohort ->
            listOf("active", "idle").map { state ->
                PlayerTimeMeterKey(state, cohort) to
                    Counter
                        .builder("arc_product_player_time")
                        .description("Observed player time classified by recent activity")
                        .baseUnit("seconds")
                        .tags("state", state, "cohort", cohort.label)
                        .register(registry)
            }
        }.toMap()
    private val detailCounters =
        ProductDetailType.entries.associateWith { type ->
            Counter
                .builder("arc_product_detail_events")
                .description("Detailed journey observations retained outside Prometheus labels")
                .tag("dimension", type.label)
                .register(registry)
        }
    private val qaCounters =
        listOf(
            "session_start",
            "first_join",
            "command",
            "world",
            "teleport",
            "npc",
            "action",
            "feature",
            "outcome",
            "onboarding_hint",
            "session_end",
            "session_censored",
            *ProductUiKind.entries.map { "ui_${it.name.lowercase()}" }.toTypedArray(),
        )
            .associateWith { event ->
                Counter
                    .builder("arc_product_qa_events")
                    .description("Known QA-player telemetry observations excluded from organic aggregation")
                    .tag("event", event)
                    .register(registry)
            }
    private val movementCounters =
        ProductWorldType.entries.associateWith { world ->
            Counter
                .builder("arc_product_movement_distance")
                .description("Observed non-teleport movement distance")
                .baseUnit("blocks")
                .tag("world_type", world.label)
                .register(registry)
        }
    private val transitionCounters =
        ProductWorldType.entries.flatMap { from ->
            ProductWorldType.entries.map { to ->
                TransitionMeterKey(from, to) to
                    Counter
                        .builder("arc_product_world_transitions")
                        .description("Observed player world transitions by bounded world type")
                        .tags("from", from.label, "to", to.label)
                        .register(registry)
            }
        }.toMap()
    private val teleportCounters =
        ProductTeleportType.entries.flatMap { cause ->
            ProductWorldType.entries.flatMap { from ->
                ProductWorldType.entries.map { to ->
                    TeleportMeterKey(cause, from, to) to
                        Counter
                            .builder("arc_product_teleports")
                            .description("Successful player teleports by bounded cause and world type")
                            .tags("cause", cause.label, "from", from.label, "to", to.label)
                            .register(registry)
                }
            }
        }.toMap()

    private val uiRedisListener = ChannelListener { _, message, origin -> receiveUi(message, origin) }
    private val redisListener = ChannelListener { _, message, origin -> receive(message, origin) }

    fun start() {
        if (config.networkEnabled && redis != null) {
            redis.registerChannelUnique(CHANNEL, redisListener)
            redis.registerChannelUnique(ProductUiCodec.CHANNEL, uiRedisListener)
        }
    }

    @Synchronized
    fun join(
        playerId: String,
        firstJoin: Boolean,
        sample: ProductPlayerSample,
        qa: Boolean = false,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        sessions.remove(player)?.let { finish(it, now, organic = false) }
        val session =
            Session(
                player = player,
                cohort = if (firstJoin) ProductCohort.NEW else ProductCohort.RETURNING,
                qa = qa,
                startedAt = now,
                lastSampleAt = now,
                lastActiveAt = now,
                lastWorld = sample.world,
                lastWorldName = normalizedWorld(sample.worldName),
                lastX = sample.x,
                lastY = sample.y,
                lastZ = sample.z,
            )
        session.addTrail("world", session.lastWorldName)
        sessions[player] = session
        if (qa) {
            qa("session_start")
            if (firstJoin) qa("first_join")
            qa("world")
            return
        }
        recordLocal(signal(player, now, ProductEventKind.SESSION_START))
        // A first visit to another backend is not a new network player.
        if (firstJoin && (primaryAggregator || !config.networkEnabled)) {
            recordLocal(signal(player, now, ProductEventKind.FIRST_JOIN))
        }
        detail(player, now, ProductDetailType.WORLD, normalizedWorld(sample.worldName))
    }

    /** Seed players that were online before an ARC reload without inventing a join. */
    @Synchronized
    fun trackExisting(
        playerId: String,
        sample: ProductPlayerSample,
        qa: Boolean = false,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val session =
            Session(
                player = player,
                cohort = ProductCohort.OBSERVED,
                qa = qa,
                startedAt = now,
                lastSampleAt = now,
                lastActiveAt = now,
                lastWorld = sample.world,
                lastWorldName = normalizedWorld(sample.worldName),
                lastX = sample.x,
                lastY = sample.y,
                lastZ = sample.z,
            )
        session.addTrail("world", session.lastWorldName)
        sessions.putIfAbsent(player, session)
    }

    @Synchronized
    fun leave(
        playerId: String,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        sessions.remove(player)?.let { finish(it, now, organic = true) }
    }

    @Synchronized
    fun command(
        playerId: String,
        message: String,
        now: Long = clockMillis(),
    ) {
        val root = ProductCommandClassifier.root(message) ?: return
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.apply {
            lastActiveAt = now
            lastCommand = root
            addTrail("command", root)
        }
        val interest = ProductCommandClassifier.classify(message)
        if (session?.qa == true) {
            qa("command")
            interest?.feature?.let { feature ->
                session.lastFeature = feature
                session.lastActivity = feature.activity
                if (feature.countsAsSystem) session.systems += feature.label
            }
            return
        }
        detail(player, now, ProductDetailType.COMMAND, root)
        if (interest == null) return
        val feature = interest.feature
        session?.apply {
            lastActiveAt = now
            if (feature.countsAsSystem) systems += feature.label
            lastFeature = feature
            lastActivity = feature.activity
        }
        featureCounters.getValue(FeatureMeterKey(feature, ProductEntryPoint.COMMAND)).increment()
        if (feature.path != ProductPath.NONE) {
            recordLocal(
                signal(
                    player = player,
                    now = now,
                    kind = ProductEventKind.PATH_INTEREST,
                    path = feature.path,
                    activity = feature.activity,
                ),
            )
        }
        recordLocal(
            signal(
                player = player,
                now = now,
                kind = ProductEventKind.FEATURE_INTEREST,
                path = feature.path,
                feature = feature,
                activity = feature.activity,
            ),
        )
        if (interest.event != ProductEventKind.FEATURE_INTEREST && interest.event != ProductEventKind.MENU_OPEN) {
            recordLocal(signal(player, now, interest.event, feature = feature, activity = feature.activity))
        }
    }

    @Synchronized
    fun pathChoice(
        playerId: String,
        path: ProductPath,
        now: Long = clockMillis(),
    ) {
        if (path == ProductPath.NONE) return
        val player = ProductPseudonym.of(playerId)
        sessions[player]?.let { session ->
            session.lastActiveAt = now
            session.addTrail("path", path.label)
            if (session.qa) {
                qa("feature")
                return
            }
        }
        recordLocal(signal(player, now, ProductEventKind.PATH_CHOICE, path = path))
    }

    @Synchronized
    fun featureInterest(
        playerId: String,
        feature: ProductFeature,
        entry: ProductEntryPoint,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.addTrail("feature", feature.label)
        if (session?.qa == true) {
            session.lastActiveAt = now
            if (feature.countsAsSystem) session.systems += feature.label
            session.lastFeature = feature
            session.lastActivity = feature.activity
            qa("feature")
            return
        }
        featureCounters.getValue(FeatureMeterKey(feature, entry)).increment()
        session?.apply {
            lastActiveAt = now
            if (feature.countsAsSystem) systems += feature.label
            lastFeature = feature
            lastActivity = feature.activity
        }
        if (feature.path != ProductPath.NONE) {
            recordLocal(signal(player, now, ProductEventKind.PATH_INTEREST, path = feature.path, activity = feature.activity))
        }
        recordLocal(signal(player, now, ProductEventKind.FEATURE_INTEREST, path = feature.path, feature = feature, activity = feature.activity))
    }

    @Synchronized
    fun onboardingHint(
        playerId: String,
        hint: ProductOnboardingHint,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.apply {
            lastActiveAt = now
            addTrail("onboarding_hint", hint.label)
        }
        if (session?.qa == true) {
            qa("onboarding_hint")
            return
        }
        detail(player, now, ProductDetailType.ONBOARDING_HINT, hint.label)
    }

    @Synchronized
    fun outcome(
        playerId: String,
        outcome: ProductOutcome,
        feature: ProductFeature? = null,
        entry: ProductEntryPoint = ProductEntryPoint.API,
        now: Long = clockMillis(),
    ) {
        if (!outcome.isGameplayResult()) return
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.addTrail("outcome", outcome.label)
        if (session?.qa == true) {
            session.lastActiveAt = now
            feature?.takeIf(ProductFeature::countsAsSystem)?.let { session.systems += it.label }
            session.lastFeature = feature
            session.lastActivity = outcome.activity
            qa("outcome")
            return
        }
        outcomeCounters.getValue(outcome).increment()
        feature?.let {
            featureCounters.getValue(FeatureMeterKey(it, entry)).increment()
            if (it.countsAsSystem) session?.systems?.add(it.label)
        }
        session?.apply {
            lastActiveAt = now
            feature?.takeIf(ProductFeature::countsAsSystem)?.let { systems += it.label }
            lastFeature = feature
            lastActivity = outcome.activity
        }
        recordLocal(
            signal(
                player = player,
                now = now,
                kind = ProductEventKind.MEANINGFUL_OUTCOME,
                path = outcome.path,
                feature = feature,
                activity = outcome.activity,
                outcome = outcome,
            ),
        )
    }

    @Synchronized
    fun action(
        playerId: String,
        action: ProductAction,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.addTrail("action", action.label)
        if (session?.qa == true) {
            session.lastActiveAt = now
            session.actionCount++
            session.lastActivity = action.activity
            qa("action")
            return
        }
        actionCounters.getValue(action).increment()
        session?.apply {
            lastActiveAt = now
            actionCount++
            lastActivity = action.activity
        }
        val firstActivity = store.recordAction(player, action, now)
        if (firstActivity) {
            recordLocal(signal(player, now, ProductEventKind.ACTIVITY, activity = action.activity))
        }
        val threshold = config.threshold(action) ?: return
        if (store.actionCount(player, action, now) == threshold.first.toLong()) {
            outcome(playerId, threshold.second, entry = ProductEntryPoint.GAMEPLAY, now = now)
        }
    }

    @Synchronized
    fun worldChange(
        playerId: String,
        from: ProductWorldType,
        to: ProductWorldType,
        toWorldName: String,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player]
        session?.apply {
            lastWorld = to
            lastWorldName = normalizedWorld(toWorldName)
            lastX = null
            lastY = null
            lastZ = null
            addTrail("world", lastWorldName)
        }
        if (session?.qa == true) {
            qa("world")
            return
        }
        transitionCounters.getValue(TransitionMeterKey(from, to)).increment()
        detail(player, now, ProductDetailType.WORLD, normalizedWorld(toWorldName))
        action(playerId, ProductAction.WORLD_CHANGE, now)
    }

    @Synchronized
    fun teleport(
        playerId: String,
        from: ProductWorldType,
        to: ProductWorldType,
        toWorldName: String,
        rawCause: String,
        now: Long = clockMillis(),
    ) {
        val player = ProductPseudonym.of(playerId)
        val cause = ProductWireCodec.normalizeCause(rawCause) ?: "unknown"
        val session = sessions[player]
        session?.apply {
            lastActiveAt = now
            lastWorld = to
            lastWorldName = normalizedWorld(toWorldName)
            lastTeleportCause = cause
            lastActivity = ProductActivity.EXPLORATION
            lastX = null
            lastY = null
            lastZ = null
            addTrail("teleport", lastWorldName)
            addTrail("cause", cause)
        }
        if (session?.qa == true) {
            qa("teleport")
            return
        }
        teleportCounters.getValue(TeleportMeterKey(ProductTeleportType.classify(rawCause), from, to)).increment()
        detail(player, now, ProductDetailType.TELEPORT_WORLD, normalizedWorld(toWorldName))
        detail(player, now, ProductDetailType.TELEPORT_CAUSE, cause)
    }

    @Synchronized
    fun npcClick(
        playerId: String,
        npcId: Int,
        npcName: String?,
        now: Long = clockMillis(),
    ) {
        if (npcId < 0) return
        val player = ProductPseudonym.of(playerId)
        val id = npcId.toString()
        val display = ProductWireCodec.sanitizeDisplay(npcName)
        val session = sessions[player]
        session?.apply {
            lastActiveAt = now
            lastNpcId = id
            lastNpcName = display
            lastActivity = ProductActivity.DISCOVERY
            addTrail("npc", id)
        }
        if (session?.qa == true) {
            qa("npc")
            return
        }
        detail(player, now, ProductDetailType.NPC, id, display)
    }

    /** Actual UI lifecycle observations from the shared renderer or a verified native adapter. */
    @Synchronized
    fun ui(playerId: String, kind: ProductUiKind, view: ProductUiView, button: String = "_menu",
        durationMillis: Long = 0, now: Long = clockMillis()) {
        val player = ProductPseudonym.of(playerId)
        val session = sessions[player] ?: return // Never treat unattached/offline or startup viewers as new organic sessions.
        if (session.qa) { qa("ui_${kind.name.lowercase()}"); return }
        session.lastActiveAt = now
        val feature = view.buttons[button]?.feature ?: productUiFeature(view.surface, button)
        val event = ProductUiSignal(ProductPseudonym.eventId(), serverName, player, now, kind,
            view.surface, view.revision, button, feature?.label, durationMillis)
        if (!ProductUiCodec.valid(event)) return
        if (kind == ProductUiKind.OPEN) recordLocal(signal(player, now, ProductEventKind.MENU_OPEN))
        seenEvents[event.eventId] = Unit
        if (!store.applyUi(event) || !config.networkEnabled) return
        val publisher = redis
        if (publisher == null) { transportCounters.getValue("publish_unavailable").increment(); return }
        transportCounters.getValue("publish_attempt").increment()
        publisher.publish(ProductUiCodec.CHANNEL, gson.toJson(event))
    }

    @Synchronized
    private fun receiveUi(payload: String, origin: String) {
        val event = ProductUiCodec.decode(payload, origin, clockMillis(), config.retentionDays, gson)
        if (event == null) { transportCounters.getValue("invalid").increment(); return }
        if (seenEvents.put(event.eventId, Unit) != null) { transportCounters.getValue("duplicate").increment(); return }
        transportCounters.getValue("receive").increment()
        store.applyUi(event)
    }

    /** Sample movement and active/idle time without a hot PlayerMoveEvent listener. */
    @Synchronized
    fun sample(
        samples: Collection<ProductPlayerSample>,
        now: Long = clockMillis(),
    ) {
        samples.forEach { sample ->
            val player = ProductPseudonym.of(sample.playerId)
            val session = sessions[player] ?: return@forEach
            val elapsed = ((now - session.lastSampleAt).coerceIn(0, MAX_SAMPLE_GAP_MILLIS) / 1_000.0)
            val distance = session.distance(sample)
            if (session.qa) {
                if (distance != null && distance in config.movementThresholdBlocks..MAX_MOVEMENT_SAMPLE_BLOCKS) qa("action")
                session.lastSampleAt = now
                session.lastWorld = sample.world
                session.lastWorldName = normalizedWorld(sample.worldName)
                session.lastX = sample.x
                session.lastY = sample.y
                session.lastZ = sample.z
                return@forEach
            }
            if (distance != null && distance in config.movementThresholdBlocks..MAX_MOVEMENT_SAMPLE_BLOCKS) {
                movementCounters.getValue(sample.world).increment(distance)
                action(sample.playerId, ProductAction.MOVE, now)
            }
            val active = now - session.lastActiveAt <= config.activeWindowSeconds * 1_000L
            if (elapsed > 0) {
                playerTimeCounters.getValue(PlayerTimeMeterKey(if (active) "active" else "idle", session.cohort)).increment(elapsed)
                if (active) session.activeSeconds += elapsed.toLong()
            }
            session.lastSampleAt = now
            session.lastWorld = sample.world
            session.lastWorldName = normalizedWorld(sample.worldName)
            session.lastX = sample.x
            session.lastY = sample.y
            session.lastZ = sample.z
        }
    }

    @Synchronized
    fun snapshot(
        networkReady: Boolean,
        now: Long = clockMillis(),
    ): List<MetricPoint> {
        val local =
            listOf(
                MetricPoint("arc_product_telemetry_enabled", "Product-interest telemetry enabled state", 1.0),
                MetricPoint("arc_product_telemetry_primary", "Whether this node owns network rolling gauges", if (primaryAggregator) 1.0 else 0.0),
                MetricPoint("arc_product_telemetry_network_ready", "Whether Redis transport is currently connected", if (networkReady) 1.0 else 0.0),
                MetricPoint("arc_product_online_sessions", "Product sessions currently observed on this node", sessions.size.toDouble()),
                MetricPoint(
                    "arc_product_active_sessions",
                    "Product sessions active inside the configured activity window",
                    sessions.values.count { now - it.lastActiveAt <= config.activeWindowSeconds * 1_000L }.toDouble(),
                ),
                MetricPoint(
                    "arc_product_telemetry_recovered_invalid_state",
                    "Whether invalid persisted state was moved aside at startup",
                    if (store.recoveredInvalidPath != null) 1.0 else 0.0,
                ),
            )
        return if (primaryAggregator) local + store.snapshot(now, "network") else local
    }

    @Synchronized
    fun report(
        days: Int,
        limit: Int,
        networkReady: Boolean,
        now: Long = clockMillis(),
    ): Map<String, Any?> {
        val report = LinkedHashMap(store.report(now, days, limit))
        report["scope"] = if (primaryAggregator) "network" else "local-standby"
        report["primary"] = primaryAggregator
        report["networkReady"] = networkReady
        report["complete"] = report["complete"] == true && primaryAggregator && (!config.networkEnabled || networkReady)
        report["transportGuarantee"] = "Best-effort Redis pub/sub; current connectivity does not prove historical delivery"
        if (!primaryAggregator) report["warning"] = "Rolling network detail is exposed by the primary ARC node"
        return report
    }

    @Synchronized
    fun shutdown(now: Long = clockMillis()) {
        sessions.values.toList().forEach { finish(it, now, organic = false) }
        sessions.clear()
        store.flush(now, force = true)
        if (config.networkEnabled && redis != null) {
            redis.unregisterChannel(CHANNEL, redisListener)
            redis.unregisterChannel(ProductUiCodec.CHANNEL, uiRedisListener)
        }
    }

    fun flush(now: Long = clockMillis()): Boolean = store.flush(now)

    @Synchronized
    private fun receive(
        payload: String,
        origin: String,
    ) {
        val now = clockMillis()
        val signal = ProductWireCodec.decode(payload, origin, now, config.retentionDays, gson)
        if (signal == null) {
            transportCounters.getValue("invalid").increment()
            return
        }
        if (seenEvents.put(signal.eventId, Unit) != null) {
            transportCounters.getValue("duplicate").increment()
            return
        }
        transportCounters.getValue("receive").increment()
        store.apply(signal)
    }

    private fun finish(
        session: Session,
        now: Long,
        organic: Boolean,
    ) {
        if (session.qa) {
            qa(if (organic) "session_end" else "session_censored")
            return
        }
        val elapsed = ((now - session.lastSampleAt).coerceIn(0, MAX_SAMPLE_GAP_MILLIS) / 1_000.0)
        if (elapsed > 0 && now - session.lastActiveAt <= config.activeWindowSeconds * 1_000L) {
            playerTimeCounters.getValue(PlayerTimeMeterKey("active", session.cohort)).increment(elapsed)
            session.activeSeconds += elapsed.toLong()
        }
        val durationSeconds = ((now - session.startedAt).coerceAtLeast(0) / 1_000L).coerceAtMost(MAX_SESSION_SECONDS)
        val kind = if (organic) ProductEventKind.SESSION_END else ProductEventKind.SESSION_CENSORED
        if (organic && durationSeconds > 0) {
            sessionDuration.getValue(session.cohort).record(durationSeconds, TimeUnit.SECONDS)
            sessionActiveDuration.getValue(session.cohort).record(session.activeSeconds.coerceAtMost(durationSeconds), TimeUnit.SECONDS)
            sessionSystems.getValue(session.cohort).record(session.systems.size.toDouble())
            sessionActions.getValue(session.cohort).record(session.actionCount.toDouble())
        }
        val exitStage = exitStage(session.player)
        recordLocal(
            signal(
                player = session.player,
                now = now,
                kind = kind,
                sessionSeconds = durationSeconds,
                activeSeconds = session.activeSeconds.coerceAtMost(durationSeconds),
                systems = session.systems,
                exit =
                    if (organic) {
                        ProductExitContext(
                            server = serverName,
                            world = session.lastWorldName,
                            command = session.lastCommand,
                            npcId = session.lastNpcId,
                            npcName = session.lastNpcName,
                            feature = session.lastFeature,
                            activity = session.lastActivity,
                            stage = exitStage,
                            teleportCause = session.lastTeleportCause,
                            trail = session.trail.toList(),
                        )
                    } else {
                        null
                    },
            ),
        )
    }

    private fun exitStage(player: String): ProductExitStage {
        val journey = store.journey(player) ?: return ProductExitStage.ENGAGED
        return when {
            journey.firstGameplayOutcomeAt.isNotEmpty() -> ProductExitStage.ENGAGED
            journey.firstPathAt.isNotEmpty() -> ProductExitStage.BEFORE_OUTCOME
            journey.firstMenuAt != null -> ProductExitStage.BEFORE_PATH
            else -> ProductExitStage.BEFORE_MENU
        }
    }

    private fun detail(
        player: String,
        now: Long,
        type: ProductDetailType,
        key: String?,
        display: String? = null,
    ) {
        val safeKey = key?.takeIf { ProductWireCodec.isValidDetailKey(type, it) } ?: return
        detailCounters.getValue(type).increment()
        recordLocal(signal(player, now, ProductEventKind.DETAIL, detail = ProductDetail(type, safeKey, ProductWireCodec.sanitizeDisplay(display))))
    }

    private fun qa(event: String) {
        qaCounters[event]?.increment()
    }

    private fun recordLocal(signal: ProductSignal) {
        transportCounters.getValue("local").increment()
        seenEvents[signal.eventId] = Unit
        eventCounters.getValue(EventMeterKey(signal.kind, signal.path)).increment()
        val result = store.apply(signal)
        if (result.changed) recordLatency(signal, result.journeyBefore)
        if (!result.changed || !config.networkEnabled) return
        val publisher = redis
        if (publisher == null) {
            transportCounters.getValue("publish_unavailable").increment()
            return
        }
        transportCounters.getValue("publish_attempt").increment()
        publisher.publish(CHANNEL, ProductWireCodec.encode(signal, gson))
    }

    private fun recordLatency(
        signal: ProductSignal,
        before: ProductJourneySnapshot?,
    ) {
        // Daily-set changes are not new lifetime milestones.
        if (before?.firstJoinAt == null) return
        when (signal.kind) {
            ProductEventKind.MENU_OPEN -> if (before.firstMenuAt != null) return
            ProductEventKind.PATH_INTEREST,
            ProductEventKind.PATH_CHOICE,
            -> if (signal.path in before.firstPathAt) return
            ProductEventKind.MEANINGFUL_OUTCOME -> if (signal.path in before.firstGameplayOutcomeAt) return
            else -> return
        }
        val start =
            when (signal.kind) {
                ProductEventKind.MENU_OPEN -> before.firstJoinAt to LatencyMeterKey("join_to_menu", ProductPath.NONE)
                ProductEventKind.PATH_INTEREST,
                ProductEventKind.PATH_CHOICE,
                -> before.firstMenuAt to LatencyMeterKey("menu_to_path", signal.path)
                ProductEventKind.MEANINGFUL_OUTCOME -> before.firstPathAt[signal.path] to LatencyMeterKey("path_to_outcome", signal.path)
                else -> return
            }
        val startedAt = start.first ?: return
        val elapsed = signal.occurredAt - startedAt
        if (elapsed !in 0..MAX_FUNNEL_LATENCY_MILLIS) return
        latencyTimers[start.second]?.record(elapsed, TimeUnit.MILLISECONDS)
    }

    private fun signal(
        player: String,
        now: Long,
        kind: ProductEventKind,
        path: ProductPath = ProductPath.NONE,
        feature: ProductFeature? = null,
        activity: ProductActivity? = null,
        outcome: ProductOutcome? = null,
        detail: ProductDetail? = null,
        exit: ProductExitContext? = null,
        sessionSeconds: Long = 0,
        activeSeconds: Long = 0,
        systems: Set<String> = emptySet(),
    ): ProductSignal =
        ProductSignal(
            eventId = ProductPseudonym.eventId(),
            source = serverName,
            player = player,
            occurredAt = now,
            kind = kind,
            path = path,
            feature = feature,
            activity = activity,
            outcome = outcome,
            detail = detail,
            exit = exit,
            sessionSeconds = sessionSeconds,
            activeSeconds = activeSeconds,
            systems = systems,
        )

    private fun Session.distance(sample: ProductPlayerSample): Double? {
        if (lastWorld != sample.world) return null
        val x = lastX ?: return null
        val y = lastY ?: return null
        val z = lastZ ?: return null
        val dx = sample.x - x
        val dy = sample.y - y
        val dz = sample.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun Session.addTrail(
        kind: String,
        value: String?,
    ) {
        val step = value?.let { ProductWireCodec.trailStep(kind, it) } ?: return
        if (trail.peekLast() == step) return
        while (trail.size >= MAX_TRAIL_STEPS) trail.removeFirst()
        trail.addLast(step)
    }

    private fun normalizeServer(value: String): String = ProductWireCodec.normalizeServer(value) ?: "unknown"

    private fun normalizedWorld(value: String): String = ProductWireCodec.normalizeWorld(value) ?: "unknown"

    companion object {
        const val CHANNEL = ProductWireCodec.CHANNEL
        private const val MAX_SEEN_EVENTS = 8_192
        private const val MAX_SAMPLE_GAP_MILLIS = 60_000L
        private const val MAX_MOVEMENT_SAMPLE_BLOCKS = 128.0
        private const val MAX_SESSION_SECONDS = 24 * 60 * 60L
        private const val MAX_FUNNEL_LATENCY_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        private const val MAX_TRAIL_STEPS = 12
    }
}
