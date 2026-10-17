package ru.arc.metrics

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

private val telemetryConfigLoad = Any()

/** Worker-only fresh YAML. Serializes first-install copying across both telemetry owners;
 * deliberately avoids ConfigManager's cache and its main-thread global reload sweep. */
internal fun loadTelemetryConfig(dataPath: Path, name: String): Config = synchronized(telemetryConfigLoad) {
    Config(dataPath, ConfigManager.moduleYamlRelative(dataPath, name))
}
