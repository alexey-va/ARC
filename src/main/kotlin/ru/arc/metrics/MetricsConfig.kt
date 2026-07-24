package ru.arc.metrics

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager

/**
 * Prometheus scrape endpoint for Paper server health gauges ([MetricsModule]).
 */
open class MetricsConfig(private val config: Config) {

    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val bindHost: String
        get() = config.string("bind-host", "127.0.0.1")

    open val bindPort: Int
        get() = config.integer("bind-port", 9950)

    /** How often cheap gauges refresh (TPS, memory, players). */
    open val sampleIntervalSeconds: Int
        get() = config.integer("sample-interval-seconds", 5).coerceIn(1, 60)

    /** How often expensive world scans run (loaded chunks). */
    open val heavySampleIntervalSeconds: Int
        get() = config.integer("heavy-sample-interval-seconds", 60).coerceIn(10, 300)

    open val includeLoadedChunks: Boolean
        get() = config.bool("include-loaded-chunks", true)

    companion object {
        @Volatile
        private var instance: MetricsConfig = MetricsConfig(EmptyConfig)

        fun current(): MetricsConfig = instance

        fun reload() {
            val cfg = ConfigManager.ofModule(ARC.instance.dataPath, "metrics.yml")
            instance = MetricsConfig(cfg)
        }
    }
}

private object EmptyConfig : Config(
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
    "empty-metrics.yml",
)
