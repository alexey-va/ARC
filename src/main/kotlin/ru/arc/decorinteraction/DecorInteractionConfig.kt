package ru.arc.decorinteraction

import ru.arc.config.ConfigManager
import java.nio.file.Path

internal data class DecorInteractionConfig(val enabled: Boolean) {
    companion object {
        fun load(dataPath: Path): DecorInteractionConfig {
            val source = ConfigManager.of(dataPath, "modules/decor-interactions.yml")
            source.mergeMissingFromBundled("modules/decor-interactions.yml")
            return DecorInteractionConfig(enabled = source.bool("enabled", false))
        }
    }
}
