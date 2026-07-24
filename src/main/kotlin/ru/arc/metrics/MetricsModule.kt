package ru.arc.metrics

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.info

/**
 * Low-overhead Prometheus gauges for TPS, JVM memory, players, Redis, chunks.
 *
 * Values are cached on a slow repeating task; scrape only reads the registry.
 */
object MetricsModule : PluginModule {
    override val name = "Metrics"
    override val priority = 34

    private var registry: PrometheusMeterRegistry? = null
    private var httpServer: MetricsHttpServer? = null
    private var sampler: MetricsSampler? = null
    private var cheapTask: ScheduledTask? = null
    private var heavyTask: ScheduledTask? = null

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        ConfigManager.reloadAll()
        MetricsConfig.reload()
        val cfg = MetricsConfig.current()
        if (!cfg.enabled) {
            shutdown()
            info("Prometheus metrics disabled")
            return
        }

        val prom = registry ?: PrometheusMeterRegistry(PrometheusConfig.DEFAULT).also { registry = it }
        val sample = MetricsSampler(prom)
        sampler = sample
        httpServer = MetricsHttpServer(prom).also { it.start() }

        cheapTask?.cancel()
        heavyTask?.cancel()
        val cheapTicks = (cfg.sampleIntervalSeconds * 20).coerceAtLeast(20)
        cheapTask =
            repeating(cheapTicks.ticks, delay = 40.ticks) {
                sample.sampleCheap()
            }
        val heavyTicks = (cfg.heavySampleIntervalSeconds * 20).coerceAtLeast(200)
        heavyTask =
            repeating(heavyTicks.ticks, delay = 200.ticks) {
                sample.sampleHeavy()
            }
        sample.sampleCheap()
    }

    override fun reload() {
        httpServer?.stop()
        Thread.sleep(50)
        init()
    }

    override fun shutdown() {
        cheapTask?.cancel()
        heavyTask?.cancel()
        cheapTask = null
        heavyTask = null
        httpServer?.stop()
        httpServer = null
        sampler = null
        registry?.close()
        registry = null
    }
}
