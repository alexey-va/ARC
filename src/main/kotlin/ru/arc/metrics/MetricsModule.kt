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
import ru.arc.config.ConfigManager
import ru.arc.core.EventScope
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
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
        productInterest?.measurementResetStatus() ?: emptyMap()

    fun readMeasurementReset(): java.util.concurrent.CompletableFuture<MeasurementResetState?> =
        productInterest?.readMeasurementReset()
            ?: java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("Product telemetry is unavailable"))

    private var runtime: ArcMetricsRuntime? = null
    private var collector: PaperMetricsCollector? = null
    private var redisMetrics: RedisMetricsBinder? = null
    private var dungeonInterest: DungeonInterestMetrics? = null
    private var productInterest: ProductInterestTelemetry? = null
    private var externalProductTopic: ValidatedRedisTopic<ExternalProductEnvelope>? = null
    private var jobWorkTopic: ValidatedRedisTopic<JobWorkEnvelope>? = null
    private var productUi: ProductUiListener? = null
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
    ): Boolean {
        val product = productInterest ?: return false
        return runCatching {
            when {
                action != null -> product.action(playerId, action)
                outcome != null -> product.outcome(playerId, outcome, feature, ProductEntryPoint.GAMEPLAY)
                feature != null -> product.featureInterest(playerId, feature, ProductEntryPoint.GAMEPLAY)
                else -> error("empty external product signal")
            }
            true
        }.onFailure { warn("External product telemetry rejected: {}", it.message ?: it::class.simpleName) }
            .getOrDefault(false)
    }

    internal fun recordExternalEvent(
        playerId: java.util.UUID,
        source: ExternalProductSource,
        event: ExternalProductEvent,
        operationId: String,
    ): Boolean {
        val product = productInterest ?: return false
        val occurredAt = System.currentTimeMillis()
        val envelope = ExternalProductEnvelope(
            origin = ARC.serverName ?: "unknown",
            player = ExternalProductEnvelopeCodec.player(playerId),
            source = source.label,
            event = event.label,
            operationId = operationId,
            occurredAt = occurredAt,
        )
        val local = product.externalEvent(envelope.player, source, event, occurredAt)
        if (!local) return false
        val topic = externalProductTopic
        if (topic == null || ARC.redisManager == null) return local
        return runCatching { topic.publish(envelope); local }.getOrDefault(local)
    }

    internal fun recordJobWork(playerId: java.util.UUID, job: String): Boolean {
        val player = ExternalProductEnvelopeCodec.player(playerId)
        val observation = productInterest?.observeJobWork(player, job) ?: return false
        val envelope = JobWorkEnvelope(
            origin = ARC.serverName ?: "unknown", player = player,
            operationId = java.util.UUID.randomUUID().toString(), job = job,
            startedAt = observation.startedAt, endedAt = observation.endedAt,
        )
        // Best-effort product telemetry; financial ownership and payout results stay in the audit ledger.
        runCatching { jobWorkTopic?.publish(envelope) }
        return true
    }

    internal fun breakJobWork(playerId: java.util.UUID) {
        productInterest?.breakJobWork(ExternalProductEnvelopeCodec.player(playerId))
    }

    fun productInterestReport(
        days: Int,
        limit: Int,
    ): Map<String, Any?> =
        productInterest?.report(days, limit, ARC.redisManager?.isConnected() == true)?.plus("uiCoverage" to productUi?.coverage())
            ?: linkedMapOf("complete" to false, "status" to "disabled", "message" to "Product-interest telemetry is not active")

    /** Let feature modules publish cached snapshots without owning scrape-time work. */
    fun recordSnapshot(
        source: String,
        tier: String,
        snapshot: () -> Collection<MetricPoint>,
    ) {
        runtime?.recordSnapshot(source, tier, snapshot)
    }

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        shutdown()

        val moduleConfig = ConfigManager.ofModule(ARC.instance.dataPath, "metrics.yml")
        val cfg = MetricsConfig(moduleConfig)
        if (!cfg.enabled) {
            info("Prometheus metrics disabled")
            return
        }

        val metrics =
            ArcMetricsRuntime(
                config = cfg,
                identity =
                    MetricsIdentity(
                        application = "ARC",
                        platform = "paper",
                        serverName = ARC.serverName ?: "unknown",
                        version = ARC.instance.pluginMeta.version,
                    ),
                dataPath = ARC.instance.dataPath,
            )
        val paper = PaperMetricsCollector(Bukkit.getServer())
        val redisBinder = ARC.redisManager?.let { RedisMetricsBinder(it, metrics.registry) }
        try {
            metrics.start()
            runtime = metrics
            collector = paper
            redisMetrics = redisBinder
            platformHeavyEnabled = cfg.includePlatformHeavy
            val dungeonConfig = DungeonInterestConfig.from(moduleConfig)
            activeDungeonConfig = dungeonConfig
            val productConfig = ProductInterestConfig.from(moduleConfig)
            val redisConfig = ArcRedisConfig.get()
            val product =
                if (productConfig.enabled) {
                    ProductInterestTelemetry(
                        registry = metrics.registry,
                        config = productConfig,
                        rawServerName = ARC.serverName ?: redisConfig.serverName,
                        statePath = ARC.instance.dataPath.resolve("data/product-interest-v1.json"),
                        primaryAggregator = redisConfig.mainServer,
                        redis = ARC.redisManager,
                    ).also { productInstance ->
                        productInstance.start()
                        productInterest = productInstance
                        productUi = ProductUiListener(ARC.instance, productInstance).also(ProductUiListener::start)
                        ARC.redisManager?.takeIf { productConfig.networkEnabled }?.let { redis ->
                            jobWorkTopic = ValidatedRedisTopic.open(
                                redis = redis,
                                channel = JobWorkEnvelopeCodec.CHANNEL,
                                codec = JobWorkEnvelopeCodec.codec(Common.gson),
                                originAllowed = { it.isNotBlank() },
                                embeddedOrigin = JobWorkEnvelope::origin,
                                replay = RedisReplayPolicy({ it.operationId }, 86_400_000L, 4_096),
                                onMessage = { envelope, origin ->
                                    if (origin != (ARC.serverName ?: "unknown")) {
                                        productInstance.receiveJobWork(envelope.player, envelope.observation())
                                    }
                                },
                            )
                            externalProductTopic = ValidatedRedisTopic.open(
                                redis = redis,
                                channel = ExternalProductEnvelopeCodec.CHANNEL,
                                codec = ExternalProductEnvelopeCodec.codec(Common.gson),
                                originAllowed = { it.isNotBlank() },
                                embeddedOrigin = ExternalProductEnvelope::origin,
                                replay = RedisReplayPolicy({ "${it.player}:${it.source}:${it.event}:${it.operationId}" }, 86_400_000L, 4_096),
                                onMessage = { envelope, origin ->
                                    if (origin != (ARC.serverName ?: "unknown")) {
                                        val source = ExternalProductSource.entries.firstOrNull { it.label == envelope.source }
                                        val event = ExternalProductEvent.entries.firstOrNull { it.label == envelope.event }
                                        if (source != null && event != null) productInstance.externalEvent(envelope.player, source, event, envelope.occurredAt)
                                    }
                                },
                            )
                        }
                    }
                } else {
                    null
                }
            if (dungeonConfig.enabled) {
                val tracker =
                    DungeonInterestMetrics(metrics.registry, dungeonConfig) { playerId, _ ->
                        product?.outcome(playerId, ProductOutcome.DUNGEON_VISIT, ProductFeature.DUNGEONS, ProductEntryPoint.WORLD)
                    }
                Bukkit.getWorlds().forEach { tracker.registerWorld(it.name) }
                Bukkit.getOnlinePlayers().forEach { player ->
                    tracker.trackExisting(player.uniqueId.toString(), player.world.name)
                }
                dungeonInterest = tracker
            }
            if (dungeonConfig.enabled || product != null) {
                val scope = eventScope()
                metricsEvents = scope
                scope.on<PlayerJoinEvent> { event ->
                    val player = event.player
                    dungeonInterest?.enter(player.uniqueId.toString(), player.world.name)
                    product?.join(
                        player.uniqueId.toString(),
                        firstJoin = !player.hasPlayedBefore(),
                        sample = player.sample(dungeonConfig),
                        qa = player.isProductQa(productConfig),
                    )
                }
                scope.on<PlayerChangedWorldEvent> { event ->
                    val player = event.player
                    dungeonInterest?.enter(player.uniqueId.toString(), player.world.name)
                    product?.worldChange(
                        player.uniqueId.toString(),
                        event.from.productType(dungeonConfig),
                        player.world.productType(dungeonConfig),
                        player.world.name,
                    )
                }
                scope.on<PlayerQuitEvent> { event ->
                    dungeonInterest?.leave(event.player.uniqueId.toString())
                    product?.leave(event.player.uniqueId.toString())
                }
                scope.on<WorldLoadEvent> { event ->
                    dungeonInterest?.registerWorld(event.world.name)
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
            if (product != null) {
                Bukkit.getOnlinePlayers().forEach { player ->
                    product.trackExisting(player.uniqueId.toString(), player.sample(dungeonConfig), player.isProductQa(productConfig))
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
                        metrics.recordSnapshot("product-interest", "product") {
                            product.snapshot(redis?.isConnected() == true) + ui?.snapshot().orEmpty()
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
                        runCatching { product.flush() }.onFailure { failure ->
                            warn("Product-interest persistence failed", failure)
                        }
                    }
            }
        } catch (failure: Throwable) {
            if (runtime === metrics) {
                shutdown()
            } else {
                redisBinder?.close()
                metrics.close()
            }
            throw failure
        }
    }

    private fun sampleFast() {
        val metrics = runtime ?: return
        val paper = collector ?: return
        metrics.recordSnapshot("paper-fast", "platform") {
            val redis = ARC.redisManager
            paper.fastSnapshot() +
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
        dungeonInterest?.sample()
        productUi?.refreshSnapshot()
        productInterest?.let { product ->
            val dungeonConfig = activeDungeonConfig ?: DungeonInterestConfig(enabled = false)
            product.sample(Bukkit.getOnlinePlayers().map { it.sample(dungeonConfig) })
        }
    }

    private fun sampleHeavy() {
        val metrics = runtime ?: return
        val paper = collector ?: return
        if (platformHeavyEnabled) metrics.recordSnapshot("paper-heavy", "platform-heavy", paper::heavySnapshot)
    }

    override fun reload() = init()

    override fun shutdown() {
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
        dungeonInterest?.shutdown()
        dungeonInterest = null
        productUi?.close()
        productUi = null
        jobWorkTopic?.close()
        jobWorkTopic = null
        externalProductTopic?.close()
        externalProductTopic = null
        ExternalProductTelemetryBridge.clearReplayState()
        productInterest?.shutdown()
        productInterest = null
        activeDungeonConfig = null
        platformHeavyEnabled = false
        collector = null
        redisMetrics?.close()
        redisMetrics = null
        runtime?.close()
        runtime = null
    }

    private fun Player.sample(dungeonConfig: DungeonInterestConfig): ProductPlayerSample =
        ProductPlayerSample(
            playerId = uniqueId.toString(),
            world = world.productType(dungeonConfig),
            worldName = world.name,
            x = location.x,
            y = location.y,
            z = location.z,
        )

    private fun World.productType(dungeonConfig: DungeonInterestConfig): ProductWorldType =
        ProductWorldType.classify(name, dungeonConfig.dungeonWorld(name) != null)

    private fun Player.isProductQa(config: ProductInterestConfig): Boolean = name.lowercase() in config.qaPlayerNames
}
