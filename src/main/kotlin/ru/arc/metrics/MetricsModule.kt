package ru.arc.metrics

import org.bukkit.Bukkit
import io.micrometer.core.instrument.MeterRegistry
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.core.MetricsConfig
import ru.arc.metrics.core.MetricsIdentity
import ru.arc.metrics.core.RedisMetricsBinder
import ru.arc.metrics.paper.PaperMetricsCollector
import ru.arc.util.Logging.info
import kotlin.time.Duration.Companion.seconds

/** Paper lifecycle adapter around the shared cached Prometheus runtime. */
object MetricsModule : PluginModule {
    override val name = "Metrics"
    override val priority = 34

    private var runtime: ArcMetricsRuntime? = null
    private var collector: PaperMetricsCollector? = null
    private var redisMetrics: RedisMetricsBinder? = null
    private var fastTask: ScheduledTask? = null
    private var heavyTask: ScheduledTask? = null

    fun registry(): MeterRegistry? = runtime?.registry

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        shutdown()

        val cfg = MetricsConfig(ConfigManager.ofModule(ARC.instance.dataPath, "metrics.yml"))
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
            sampleFast()
            if (cfg.includePlatformHeavy) sampleHeavy()
            fastTask =
                repeating(
                    cfg.sampleIntervalSeconds.seconds,
                    delay = cfg.sampleIntervalSeconds.seconds,
                ) {
                    sampleFast()
                }
            if (cfg.includePlatformHeavy) {
                heavyTask =
                    repeating(
                        cfg.heavySampleIntervalSeconds.seconds,
                        delay = cfg.heavySampleIntervalSeconds.seconds,
                    ) {
                        sampleHeavy()
                    }
            }
        } catch (failure: Throwable) {
            redisBinder?.close()
            metrics.close()
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
    }

    private fun sampleHeavy() {
        val metrics = runtime ?: return
        val paper = collector ?: return
        metrics.recordSnapshot("paper-heavy", "platform-heavy", paper::heavySnapshot)
    }

    override fun reload() = init()

    override fun shutdown() {
        fastTask?.cancel()
        heavyTask?.cancel()
        fastTask = null
        heavyTask = null
        collector = null
        redisMetrics?.close()
        redisMetrics = null
        runtime?.close()
        runtime = null
    }
}
