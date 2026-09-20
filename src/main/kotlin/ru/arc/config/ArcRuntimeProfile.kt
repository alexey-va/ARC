package ru.arc.config

import java.nio.file.Path

/** Bootstrap composition; changes take effect only after a server restart. */
enum class ArcRuntimeProfile {
    FULL,
    ISOLATED;

    companion object {
        fun load(dataPath: Path): ArcRuntimeProfile =
            parse(ConfigManager.of(dataPath, "modules/runtime.yml").string("profile", "full"))

        fun parse(value: String): ArcRuntimeProfile =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown ARC runtime profile '$value'; expected full or isolated")
    }
}
