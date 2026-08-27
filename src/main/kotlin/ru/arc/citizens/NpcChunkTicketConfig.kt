package ru.arc.citizens

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.Locale

internal open class NpcChunkTicketConfig(
    private val config: Config,
) {
    open val enabled: Boolean get() = config.bool("enabled", false)

    open val worlds: Set<String>
        get() =
            config
                .stringList("worlds")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { it.lowercase(Locale.ROOT) }
                .toSet()

    open val reconcileIntervalTicks: Long
        get() = config.integer("reconcile-interval-ticks", 200).toLong().coerceIn(20L, 72_000L)

    open val maxPinnedChunks: Int
        get() = config.integer("max-pinned-chunks", 64).coerceIn(1, 512)

    companion object {
        fun load(dataPath: Path): NpcChunkTicketConfig =
            NpcChunkTicketConfig(ConfigManager.of(dataPath, "modules/citizens-chunk-tickets.yml"))
    }
}
