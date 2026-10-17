package ru.arc.metrics

import io.papermc.paper.event.player.AsyncChatEvent
import net.citizensnpcs.api.event.NPCLeftClickEvent
import net.citizensnpcs.api.event.NPCRightClickEvent
import org.bukkit.Bukkit
import io.micrometer.core.instrument.MeterRegistry
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.WorldLoadEvent
import ru.arc.ARC
import ru.arc.config.ArcRedisConfig
import ru.arc.core.EventScope
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.eventScope
import ru.arc.core.repeating
import ru.arc.core.repeatingAsync
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.core.MetricsConfig
import ru.arc.metrics.core.MetricsIdentity
import ru.arc.metrics.core.RedisMetricsBinder
import ru.arc.metrics.paper.PaperMetricsCollector
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.product.ProductOnboardingHint
import ru.arc.product.ProductAction
import ru.arc.product.ProductFeature
import ru.arc.product.ProductOutcome
import ru.arc.util.Logging.info
import ru.arc.util.Common
import ru.arc.util.Logging.warn
import kotlin.time.Duration.Companion.seconds

/** Paper lifecycle adapter around the shared cached Prometheus runtime. */
object MetricsModule : PluginModule {
    override val name = "Metrics"
    override val priority = 34

    fun resetMeasurementPeriod(expectedGeneration: Long): java.util.concurrent.CompletableFuture<MeasurementResetState> =
        productInterest?.requestMeasurementReset(expectedGeneration)
            ?: java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Product telemetry is unavailable"))

    fun measurementResetStatus(): Map<String, Any?> =
        productInterest?.measurementResetStatus() ?: mapOf("state" to lifecycleState, "captureQueue" to worker?.health())

    fun readMeasurementReset(): java.util.concurrent.CompletableFuture<MeasurementResetState?> =
        productInterest?.readMeasurementReset()
            ?: java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Product telemetry is unavailable"))

    @Volatile private var runtime: ArcMetricsRuntime? = null
    @Volatile private var worker: MetricsWorkQueue? = null
    private var closing = java.util.concurrent.CompletableFuture.completedFuture(Unit)
    @Volatile private var generation = 0L
    @Volatile private var lifecycleState = "disabled"
    private var startingEvents: EventScope? = null
    private class Lifecycle(val queue: MetricsWorkQueue) {
        var metrics: ArcMetricsRuntime? = null
        var product: ProductInterestTelemetry? = null
        var dungeon: DungeonInterestMetrics? = null
        val topics = ProductTopics()
        val redis = RedisBinding()
        fun close(now: Long, atNanos: Long) {
            try {
                try { dungeon?.shutdown(atNanos) } finally { product?.shutdownOnWorker(now) }
            } finally {
                try { topics.close() }
                finally { try { redis.binder?.close() } finally { metrics?.close() } }
            }
        }
    }
    private var lifecycle: Lifecycle? = null
    private data class Prepared(val config: MetricsConfig, val dungeon: DungeonInterestConfig, val product: ProductInterestConfig)
    private var playerCapture: NativeTelemetryBatch? = null
    private var collector: PaperMetricsCollector? = null
    private class RedisBinding { var binder: RedisMetricsBinder? = null }
    private var dungeonInterest: DungeonInterestMetrics? = null
    @Volatile private var productInterest: ProductInterestTelemetry? = null
    private class ProductTopics {
        var external: ValidatedRedisTopic<ExternalProductEnvelope>? = null
        var jobs: ValidatedRedisTopic<JobWorkEnvelope>? = null
        fun close() { try { jobs?.close() } finally { external?.close() } }
    }
    @Volatile private var productTopics: ProductTopics? = null
    private var heavyCapture: BoundedPaperMetricsCapture? = null
    @Volatile private var productUi: ProductUiListener? = null
    private var activeDungeonConfig: DungeonInterestConfig? = null
    private var platformHeavyEnabled = false
    private var metricsEvents: EventScope? = null
    private var fastTask: ScheduledTask? = null
    private var heavyTask: ScheduledTask? = null
    private var productSnapshotTask: ScheduledTask? = null
    private var persistenceTask: ScheduledTask? = null
    private var measurementTask: ScheduledTask? = null

    fun registry(): MeterRegistry? = runtime?.registry

    fun recordProductPathChoice(
        player: Player,
        path: ProductPath,
    ) {
        productInterest?.pathChoice(player.uniqueId.toString(), path)
    }

    fun recordProductFeatureInterest(
        player: Player,
        feature: ProductFeature,
        entry: ProductEntryPoint = ProductEntryPoint.API,
    ) {
        productInterest?.featureInterest(player.uniqueId.toString(), feature, entry)
        ru.arc.metrics.telemetry.PlayerTelemetryModule.record(player.uniqueId, "arc", "feature.interest", feature.label,
            attributes = mapOf("entry" to entry.label))
    }

    fun recordProductOutcome(
        player: Player,
        outcome: ProductOutcome,
        feature: ProductFeature? = null,
        entry: ProductEntryPoint = ProductEntryPoint.API,
    ) {
        recordProductOutcome(player.uniqueId.toString(), outcome, feature, entry)
    }

    fun recordProductOutcome(
        playerId: String,
        outcome: ProductOutcome,
        feature: ProductFeature? = null,
        entry: ProductEntryPoint = ProductEntryPoint.API,
    ) {
        productInterest?.outcome(playerId, outcome, feature, entry)
        runCatching { java.util.UUID.fromString(playerId) }.getOrNull()?.let { id ->
            ru.arc.metrics.telemetry.PlayerTelemetryModule.record(id, "arc", "mechanic.outcome", outcome.label,
                attributes = mapOf("entry" to entry.label) + (feature?.let { mapOf("feature" to it.label) } ?: emptyMap()))
        }
    }

    fun recordProductOnboardingHint(
        player: Player,
        hint: ProductOnboardingHint,
    ) {
        productInterest?.onboardingHint(player.uniqueId.toString(), hint)
    }

    /** Called by the bounded optional external product bridge. */
    internal fun recordExternalProduct(
        playerId: String,
        feature: ProductFeature?,
        outcome: ProductOutcome?,
        action: ProductAction?,
        source: String,
        operationId: String,
    ): Boolean {
        val product = productInterest ?: return worker?.reject() ?: false
        val now = System.currentTimeMillis()
        return product.onWorker { processor ->
            if (!processor.claimExternal("signal|$playerId|$source|$operationId", now)) return@onWorker
            when {
                action != null -> processor.action(playerId, action, now)
                outcome != null -> processor.outcome(playerId, outcome, feature, ProductEntryPoint.GAMEPLAY, now)
                feature != null -> processor.featureInterest(playerId, feature, ProductEntryPoint.GAMEPLAY, now)
            }
        }
    }

    internal fun recordExternalEvent(
        playerId: java.util.UUID,
        source: ExternalProductSource,
        event: ExternalProductEvent,
        operationId: String,
    ): Boolean {
        val product = productInterest ?: return worker?.reject() ?: false
        val occurredAt = System.currentTimeMillis()
        val topics = productTopics
        val origin = ARC.serverName ?: "unknown"
        return product.onWorker { processor ->
            val wireOperationId = ProductPseudonym.of(operationId)
            if (!processor.claimExternal("event|$playerId|${source.label}|${event.label}|$wireOperationId", occurredAt)) return@onWorker
            val envelope = ExternalProductEnvelope(origin = origin, player = ExternalProductEnvelopeCodec.player(playerId),
                source = source.label, event = event.label, operationId = wireOperationId, occurredAt = occurredAt)
            if (processor.externalEvent(envelope.player, source, event, occurredAt)) topics?.external?.publish(envelope)
        }
    }

    /** true acknowledges admission, not an emitted work interval or durable/network delivery. */
    internal fun recordJobWork(playerId: java.util.UUID, job: String): Boolean {
        val product = productInterest ?: return worker?.reject() ?: false
        val topics = productTopics
        val origin = ARC.serverName ?: "unknown"
        val now = System.currentTimeMillis()
        return product.onWorker { processor ->
            val player = ExternalProductEnvelopeCodec.player(playerId)
            val observation = processor.observeJobWork(player, job, now) ?: return@onWorker
            topics?.jobs?.publish(JobWorkEnvelope(origin = origin, player = player,
                operationId = java.util.UUID.randomUUID().toString(), job = job,
                startedAt = observation.startedAt, endedAt = observation.endedAt))
        }
    }

    internal fun breakJobWork(playerId: java.util.UUID) {
        productInterest?.onWorker { it.breakJobWork(ExternalProductEnvelopeCodec.player(playerId)) }
    }

    internal fun ensureUiCapture() {
        if (productUi == null) productUi = ProductUiListener(ARC.instance, productInterest).also(ProductUiListener::start)
    }

    internal fun observeExternalUi(payload: Map<String, Any>) {
        productUi?.receive(payload)
    }

    /** Ops HTTP worker only; a gameplay caller must use the asynchronous product facade. */
    fun productInterestReport(days: Int, limit: Int): Map<String, Any?> {
        check(!Bukkit.isPrimaryThread()) { "Product reports must not wait on the Paper thread" }
        return productInterest?.report(days, limit, ARC.redisManager?.isConnected() == true)
            ?.get(30, java.util.concurrent.TimeUnit.SECONDS)?.plus("uiCoverage" to productUi?.coverage())
            ?: linkedMapOf("complete" to false, "status" to lifecycleState, "captureQueue" to worker?.health(), "message" to "Product-interest telemetry is not active")
    }

    /** Let feature modules publish cached snapshots without owning scrape-time work. */
    fun recordSnapshot(
        source: String,
        tier: String,
        snapshot: () -> Collection<MetricPoint>,
    ) {
        val metrics = runtime ?: return
        val points = snapshot().toList()
        worker?.offer { metrics.recordSnapshot(source, tier) { points } }
    }

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        shutdown()
        val epoch = ++generation
        lifecycleState = "starting"
        val plugin = ARC.instance
        val dataPath = plugin.dataPath
        val serverName = ARC.serverName ?: "unknown"
        val version = plugin.pluginMeta.version
        val redis = ARC.redisManager
        val installRedisTelemetry = plugin.redisService::installTelemetry
        val queue = MetricsWorkQueue(predecessor = closing, onFailure = { warn("Metrics asynchronous processing failed", it) })
        val owner = Lifecycle(queue)
        lifecycle = owner
        worker = queue
        // Reloads can overlap gameplay. These are explicit rejected observations until native attach.
        startingEvents = eventScope().also { scope ->
            scope.on<PlayerJoinEvent> { queue.reject() }
            scope.on<PlayerQuitEvent> { queue.reject() }
            scope.on<PlayerChangedWorldEvent> { queue.reject() }
            scope.on<PlayerCommandPreprocessEvent> { queue.reject() }
            scope.on<BlockBreakEvent>(EventPriority.MONITOR, ignoreCancelled = true) { queue.reject() }
            scope.on<BlockPlaceEvent>(EventPriority.MONITOR, ignoreCancelled = true) { queue.reject() }
            scope.on<AsyncChatEvent>(EventPriority.MONITOR, ignoreCancelled = true) { queue.reject() }
        }
        queue.submit {
            // Fresh YAML instances keep global ConfigManager.reloadAll from reloading these on Paper.
            val moduleConfig = loadTelemetryConfig(dataPath, "metrics.yml")
            val cfg = MetricsConfig(moduleConfig)
            if (!cfg.enabled) return@submit java.util.concurrent.CompletableFuture.completedFuture<Prepared?>(null)
            val metrics = ArcMetricsRuntime(cfg, MetricsIdentity("ARC", "paper", serverName, version), dataPath)
            owner.metrics = metrics
            metrics.start()
            owner.redis.binder = RedisMetricsBinder({ sink ->
                if (sink === ru.arc.redis.NoOpRedisTelemetrySink) installRedisTelemetry(sink)
                else installRedisTelemetry(object : ru.arc.redis.RedisTelemetrySink {
                    override fun onOperation(operation: ru.arc.redis.RedisOperation, result: ru.arc.redis.RedisOperationResult, durationNanos: Long) {
                        queue.offer { sink.onOperation(operation, result, durationNanos) }
                    }
                    override fun onReconnect(path: ru.arc.redis.RedisReconnectPath, result: ru.arc.redis.RedisReconnectResult) {
                        queue.offer { sink.onReconnect(path, result) }
                    }
                })
            }, metrics.registry)
            val dungeonConfig = DungeonInterestConfig.from(moduleConfig)
            val productConfig = ProductInterestConfig.from(moduleConfig)
            val redisConfig = ArcRedisConfig.get()
            val product = if (productConfig.enabled) ProductInterestTelemetry(queue, { dispatch ->
                ProductInterestProcessor(metrics.registry, productConfig, serverName,
                    dataPath.resolve("data/product-interest-v1.json"), redisConfig.mainServer, redis = redis, dispatch = dispatch)
            }).also { owner.product = it } else null
            val transportReady = queue.submit {
                if (product == null || !productConfig.networkEnabled || redis == null) return@submit
                owner.topics.jobs = ValidatedRedisTopic.open(redis = redis, channel = JobWorkEnvelopeCodec.CHANNEL,
                    codec = JobWorkEnvelopeCodec.codec(Common.gson), originAllowed = { it.isNotBlank() },
                    embeddedOrigin = JobWorkEnvelope::origin,
                    replay = RedisReplayPolicy({ it.operationId }, 86_400_000L, 4_096),
                    onMessage = { envelope, origin ->
                        if (origin != serverName) product.onWorker { it.receiveJobWork(envelope.player, envelope.observation()) }
                    })
                owner.topics.external = ValidatedRedisTopic.open(redis = redis, channel = ExternalProductEnvelopeCodec.CHANNEL,
                    codec = ExternalProductEnvelopeCodec.codec(Common.gson), originAllowed = { it.isNotBlank() },
                    embeddedOrigin = ExternalProductEnvelope::origin,
                    replay = RedisReplayPolicy({ "${it.player}:${it.source}:${it.event}:${it.operationId}" }, 86_400_000L, 4_096),
                    onMessage = { envelope, origin ->
                        if (origin != serverName) {
                            val source = ExternalProductSource.entries.firstOrNull { it.label == envelope.source }
                            val event = ExternalProductEvent.entries.firstOrNull { it.label == envelope.event }
                            if (source != null && event != null) product.onWorker { it.externalEvent(envelope.player, source, event, envelope.occurredAt) }
                        }
                    })
            }
            awaitTelemetryInitialization(queue, product?.ready, transportReady)
                .thenApply<Prepared?> { Prepared(cfg, dungeonConfig, productConfig) }
        }.thenCompose { it }.whenComplete { prepared, failure ->
            if (generation != epoch) return@whenComplete
            Tasks.scheduler.runSync(Runnable {
                if (generation != epoch || lifecycle !== owner) return@Runnable
                if (failure != null) {
                    shutdown()
                    lifecycleState = "failed"
                    warn("Metrics bootstrap failed", failure)
                } else if (prepared == null) {
                    shutdown()
                    lifecycleState = "disabled"
                } else try {
                    attach(owner, prepared.config, prepared.dungeon, prepared.product)
                    plugin.bindTelemetryRegistry(owner.metrics?.registry)
                    lifecycleState = "ready"
                } catch (attachmentFailure: Throwable) {
                    shutdown()
                    lifecycleState = "failed"
                    warn("Metrics native attachment failed", attachmentFailure)
                }
            })
        }
    }

    /** Only native lifecycle wiring and bounded captures belong on Paper. */
    private fun attach(owner: Lifecycle, cfg: MetricsConfig, dungeonConfig: DungeonInterestConfig, productConfig: ProductInterestConfig) {
        val queue = owner.queue
        val metrics = checkNotNull(owner.metrics)
        val product = owner.product
        runtime = metrics
        productInterest = product
        productTopics = owner.topics
        collector = PaperMetricsCollector(Bukkit.getServer())
        activeDungeonConfig = dungeonConfig
        platformHeavyEnabled = cfg.includePlatformHeavy
        heavyCapture = BoundedPaperMetricsCapture(Bukkit.getServer(), queue) { points ->
            metrics.recordSnapshot("paper-heavy", "platform-heavy") { points }
        }
        startingEvents?.unregisterAll(); startingEvents = null
        productUi?.close()
        productUi = ProductUiListener(ARC.instance, product).also(ProductUiListener::start)
        if (dungeonConfig.enabled) {
            val tracker = DungeonInterestMetrics(metrics.registry, dungeonConfig)
            dungeonInterest = tracker
            owner.dungeon = tracker
            val worlds = Bukkit.getWorlds().map { it.name }
            queue.offer { worlds.forEach(tracker::registerWorld) }
        }
        if (dungeonConfig.enabled || product != null) {
            val scope = eventScope()
            metricsEvents = scope
            scope.on<PlayerJoinEvent> { event ->
                val player = event.player
                captureDungeonEntry(player)
                product?.join(
                    player.uniqueId.toString(),
                    firstJoin = !player.hasPlayedBefore(),
                    sample = player.sample(dungeonConfig),
                    qa = player.isProductQa(productConfig),
                )
            }
            scope.on<PlayerChangedWorldEvent> { event ->
                val player = event.player
                captureDungeonEntry(player)
                product?.worldChange(
                    player.uniqueId.toString(),
                    event.from.productType(dungeonConfig),
                    player.world.productType(dungeonConfig),
                    player.world.name,
                )
            }
            scope.on<PlayerQuitEvent> { event ->
                val id = event.player.uniqueId.toString()
                val at = System.nanoTime()
                val tracker = dungeonInterest
                queue.offer { tracker?.leave(id, at) }
                product?.leave(event.player.uniqueId.toString())
            }
            scope.on<WorldLoadEvent> { event ->
                val world = event.world.name
                val tracker = dungeonInterest
                queue.offer { tracker?.registerWorld(world) }
            }
            if (product != null) {
                scope.on<PlayerCommandPreprocessEvent>(EventPriority.LOWEST, ignoreCancelled = false) { event ->
                    product.command(event.player.uniqueId.toString(), event.message)
                }
                scope.on<PlayerTeleportEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
                    val destination = event.to
                    product.teleport(
                        event.player.uniqueId.toString(),
                        event.from.world.productType(dungeonConfig),
                        destination.world.productType(dungeonConfig),
                        destination.world.name,
                        event.cause.name,
                    )
                }
                scope.on<BlockBreakEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
                    product.action(event.player.uniqueId.toString(), ProductAction.BLOCK_BREAK)
                }
                scope.on<BlockPlaceEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
                    product.action(event.player.uniqueId.toString(), ProductAction.BLOCK_PLACE)
                }
                scope.on<CraftItemEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
                    (event.whoClicked as? Player)?.let { player -> product.action(player.uniqueId.toString(), ProductAction.CRAFT) }
                }
                scope.on<EntityDeathEvent>(EventPriority.MONITOR) { event ->
                    if (event.entity is Player) return@on
                    event.entity.killer?.let { player -> product.action(player.uniqueId.toString(), ProductAction.MOB_KILL) }
                }
                scope.on<PlayerDeathEvent>(EventPriority.MONITOR) { event ->
                    product.action(event.player.uniqueId.toString(), ProductAction.PLAYER_DEATH)
                }
                scope.on<PlayerAdvancementDoneEvent>(EventPriority.MONITOR) { event ->
                    product.action(event.player.uniqueId.toString(), ProductAction.ADVANCEMENT)
                    product.outcome(event.player.uniqueId.toString(), ProductOutcome.ADVANCEMENT, entry = ProductEntryPoint.GAMEPLAY)
                }
                scope.on<AsyncChatEvent>(EventPriority.MONITOR, ignoreCancelled = true) { event ->
                    product.action(event.player.uniqueId.toString(), ProductAction.CHAT)
                }
                if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
                    scope.on<NPCRightClickEvent>(EventPriority.MONITOR, ignoreCancelled = false) { event ->
                        product.npcClick(event.clicker.uniqueId.toString(), event.npc.id, event.npc.fullName)
                    }
                    scope.on<NPCLeftClickEvent>(EventPriority.MONITOR, ignoreCancelled = false) { event ->
                        product.npcClick(event.clicker.uniqueId.toString(), event.npc.id, event.npc.fullName)
                    }
                }
            }
        }
        playerCapture = NativeTelemetryBatch()
        if (product != null || dungeonInterest != null) {
            playerCapture?.start(Bukkit.getOnlinePlayers().map { it.uniqueId }) { id ->
                Bukkit.getPlayer(id)?.let { player ->
                    val world = player.world.name
                    val at = System.nanoTime()
                    val tracker = dungeonInterest
                    queue.offer { tracker?.trackExisting(id.toString(), world, at) }
                    product?.trackExisting(id.toString(), player.sample(dungeonConfig), player.isProductQa(productConfig))
                }
            }
        }
        sampleFast()
        if (platformHeavyEnabled) sampleHeavy()
        fastTask =
            repeating(
                cfg.sampleIntervalSeconds.seconds,
                delay = cfg.sampleIntervalSeconds.seconds,
            ) {
                sampleFast()
            }
        if (platformHeavyEnabled) {
            heavyTask =
                repeating(
                    cfg.heavySampleIntervalSeconds.seconds,
                    delay = cfg.heavySampleIntervalSeconds.seconds,
                ) {
                    sampleHeavy()
                }
        }
        if (product != null) {
            val ui = productUi
            val redis = ARC.redisManager
            // Capture this lifecycle's instances so an in-flight callback
            // cannot publish stale data into a newly reloaded runtime.
            productSnapshotTask =
                repeatingAsync(
                    cfg.heavySampleIntervalSeconds.seconds,
                    delay = 0.seconds,
                ) {
                    val now = System.currentTimeMillis()
                    val networkReady = redis?.isConnected() == true
                    product.submit { processor ->
                        val points = processor.snapshot(networkReady, now)
                        metrics.recordSnapshot("product-interest", "product") { points + ui?.snapshot().orEmpty() + queue.metricPoints() }
                    }
                }
        }
        if (product != null) {
            // Polling only selects a new archival period after an admin confirms the reset.
            product.pollMeasurementReset().exceptionally { null }
            measurementTask = repeatingAsync(5.seconds, delay = 5.seconds) {
                product.pollMeasurementReset().exceptionally { null }
            }
            persistenceTask =
                repeatingAsync(
                    productConfig.persistIntervalSeconds.seconds,
                    delay = productConfig.persistIntervalSeconds.seconds,
                ) {
                    product.flush().exceptionally { failure ->
                        warn("Product-interest persistence failed", failure)
                        false
                    }
                }
        }
    }

    private fun captureDungeonEntry(player: Player) {
        val tracker = dungeonInterest ?: return
        val product = productInterest
        val id = player.uniqueId.toString()
        val world = player.world.name
        val atNanos = System.nanoTime()
        val atMillis = System.currentTimeMillis()
        worker?.offer {
            if (tracker.enter(id, world, atNanos)) product?.processOnWorker {
                it.outcome(id, ProductOutcome.DUNGEON_VISIT, ProductFeature.DUNGEONS, ProductEntryPoint.WORLD, atMillis)
            }
        }
    }

    private fun sampleFast() {
        val metrics = runtime ?: return
        val paper = collector ?: return
        val points = run {
            val redis = ARC.redisManager
            paper.fastSnapshot() +
                ARC.instance.visualPacketRuntime?.metricPoints().orEmpty() +
                MetricPoint(
                    "arc_redis_connected",
                    "ARC Redis connection state",
                    if (redis?.isConnected() == true) 1.0 else 0.0,
                ) +
                MetricPoint(
                    "arc_redis_subscription_active",
                    "ARC Redis subscription state",
                    if (redis?.isSubscriptionActive() == true) 1.0 else 0.0,
                ) +
                MetricPoint(
                    "arc_redis_channels",
                    "Registered ARC Redis channels",
                    (redis?.getChannelCount() ?: 0).toDouble(),
                )
        }
        val plugin = ARC.instance
        worker?.offer {
            plugin.refreshSharedServiceMetrics()
            metrics.recordSnapshot("paper-fast", "platform") { points }
            metrics.recordSnapshot("player-telemetry", "product") { ru.arc.metrics.telemetry.PlayerTelemetryModule.metricSnapshot() }
        }
        val tracker = dungeonInterest
        val at = System.nanoTime()
        worker?.offer { tracker?.sample(at) }
        productUi?.refreshSnapshot()
        productInterest?.let { product ->
            val dungeonConfig = activeDungeonConfig ?: DungeonInterestConfig(enabled = false)
            playerCapture?.start(Bukkit.getOnlinePlayers().map { it.uniqueId }) { id ->
                Bukkit.getPlayer(id)?.let { product.sample(listOf(it.sample(dungeonConfig))) }
            }
        }
    }

    private fun sampleHeavy() {
        if (platformHeavyEnabled) heavyCapture?.sample()
    }

    override fun reload() = init()

    override fun shutdown() {
        generation++
        lifecycleState = "stopped"
        if (runtime != null) ARC.instance.bindTelemetryRegistry(null)
        startingEvents?.unregisterAll(); startingEvents = null
        fastTask?.cancel()
        heavyTask?.cancel()
        productSnapshotTask?.cancel()
        persistenceTask?.cancel()
        measurementTask?.cancel()
        fastTask = null
        heavyTask = null
        productSnapshotTask = null
        persistenceTask = null
        measurementTask = null
        metricsEvents?.unregisterAll()
        metricsEvents = null
        val dungeonAt = System.nanoTime()
        dungeonInterest = null
        productUi?.close()
        productUi = null
        playerCapture?.close()
        playerCapture = null
        val owner = lifecycle
        lifecycle = null
        val queue = worker
        val now = System.currentTimeMillis()
        productInterest = null
        productTopics = null
        heavyCapture?.close()
        heavyCapture = null
        activeDungeonConfig = null
        platformHeavyEnabled = false
        collector = null
        runtime = null
        worker = null
        if (queue != null) closing = queue.closeAsync { owner?.close(now, dungeonAt) }
    }

    private fun Player.sample(dungeonConfig: DungeonInterestConfig): ProductPlayerSample {
        val position = location
        return ProductPlayerSample(uniqueId.toString(), world.productType(dungeonConfig), world.name,
            position.x, position.y, position.z)
    }

    private fun World.productType(dungeonConfig: DungeonInterestConfig): ProductWorldType =
        ProductWorldType.classify(name, dungeonConfig.dungeonWorld(name) != null)

    private fun Player.isProductQa(config: ProductInterestConfig): Boolean = name.lowercase() in config.qaPlayerNames
}

/** A failed nested initializer must never reach the native ready/registry attachment. */
internal fun awaitTelemetryInitialization(
    queue: MetricsWorkQueue,
    productReady: java.util.concurrent.CompletableFuture<Unit>?,
    transportReady: java.util.concurrent.CompletableFuture<Unit>,
): java.util.concurrent.CompletableFuture<Unit> = queue.submit {
    productReady?.join()
    transportReady.join()
}
