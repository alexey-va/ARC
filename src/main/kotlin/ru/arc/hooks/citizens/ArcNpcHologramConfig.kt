package ru.arc.hooks.citizens

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class ArcNpcHologramConfig(
    val enabled: Boolean,
    val followIntervalTicks: Long,
    val reconcileIntervalTicks: Long,
    val teleportDurationTicks: Int,
    val nameOffset: Double,
    val bodyGap: Double,
    val viewRange: Float,
    val scale: Float,
    val lineWidth: Int,
    val backgroundAlpha: Int,
    val speechDurationTicks: Int,
) {
    companion object {
        fun load(dataPath: Path): ArcNpcHologramConfig {
            val source: Config = ConfigManager.of(dataPath, "modules/npc-holograms.yml")
            return ArcNpcHologramConfig(
                enabled = source.bool("enabled", true),
                followIntervalTicks = source.integer("follow-interval-ticks", 2).toLong().coerceIn(1L, 20L),
                reconcileIntervalTicks = source.integer("reconcile-interval-ticks", 40).toLong().coerceIn(20L, 1_200L),
                teleportDurationTicks = source.integer("teleport-duration-ticks", 2).coerceIn(0, 59),
                nameOffset = source.real("layout.name-offset", 0.20).coerceIn(0.0, 2.0),
                bodyGap = source.real("layout.body-gap", 0.26).coerceIn(0.05, 2.0),
                viewRange = source.real("display.view-range", 1.0).toFloat().coerceIn(0.25f, 4.0f),
                scale = source.real("display.scale", 0.92).toFloat().coerceIn(0.25f, 2.0f),
                lineWidth = source.integer("display.line-width", 230).coerceIn(40, 1_024),
                backgroundAlpha = source.integer("display.background-alpha", 112).coerceIn(0, 255),
                speechDurationTicks = source.integer("speech.duration-ticks", 100).coerceIn(20, 600),
            )
        }
    }
}
