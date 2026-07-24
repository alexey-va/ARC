package ru.arc.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import java.nio.file.Files

class MetricsConfigTest {

    @Test
    @DisplayName("reads metrics.yml values")
    fun loadsFromYaml() {
        val dir = Files.createTempDirectory("arc-metrics-test")
        val modules = dir.resolve("modules")
        modules.toFile().mkdirs()
        Files.writeString(
            modules.resolve("metrics.yml"),
            """
            enabled: true
            bind-host: "127.0.0.1"
            bind-port: 9999
            sample-interval-seconds: 3
            heavy-sample-interval-seconds: 45
            include-loaded-chunks: false
            """.trimIndent(),
        )
        val cfg = MetricsConfig(ConfigManager.ofModule(dir, "metrics.yml"))
        assertTrue(cfg.enabled)
        assertEquals(9999, cfg.bindPort)
        assertEquals(3, cfg.sampleIntervalSeconds)
        assertEquals(45, cfg.heavySampleIntervalSeconds)
        assertTrue(!cfg.includeLoadedChunks)
    }
}
