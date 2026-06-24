package ru.arc.common.locationpools

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.ConfigSection
import java.nio.file.Path
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound

/**
 * Configuration for the location pool module.
 *
 * Uses lazy getters for automatic reload support.
 * [messages] / [editorSettings] use [ConfigSection] for the `messages` and `editor` YAML subtrees.
 */
class LocationPoolModuleConfig(
    private val config: Config,
) {
    val messages: LocationPoolMessages get() = LocationPoolMessages(config.section("messages"))
    val editorSettings: LocationPoolEditorSettings get() = LocationPoolEditorSettings(config.section("editor"))

    companion object {
        fun load(dataPath: Path): LocationPoolModuleConfig {
            val config = ConfigManager.ofModule(dataPath, "location-pools.yml")
            return LocationPoolModuleConfig(config)
        }
    }
}

/**
 * Messages for location pool editing.
 */
class LocationPoolMessages(
    private val m: ConfigSection,
) {
    val startEditing: String
        get() = m.string("start-editing", "<green>Начато редактирование пула локаций %name%!")

    val cancelEditing: String
        get() = m.string("cancel-editing", "<red>Редактирование пула локаций %name% отменено!")

    val timeoutEditing: String
        get() =
            m.string(
                "timeout-editing",
                "<red>Редактирование пула локаций %name% завершено из-за неактивности!",
            )

    val blockAdded: String
        get() = m.string("block-added", "<green>Локация добавлена! <gray>(%count%)")

    val blockRemoved: String
        get() = m.string("block-removed", "<green>Локация удалена! <gray>(%count%)")

    val notInPool: String
        get() = m.string("not-in-pool", "<red>Эта локация не в пуле! <gray>(%count%)")

    val invalidBlock: String
        get() =
            m.string(
                "invalid-block",
                "<red>Используйте <gold>золотой блок<red> для добавления или <red>красный камень<red> для удаления!",
            )
}

/**
 * Settings for the location pool editor.
 */
class LocationPoolEditorSettings(
    private val editor: ConfigSection,
) {
    private val particle = editor.section("particle")

    val particleShowIntervalTicks: Long
        get() = editor.int("particle-interval", 5).toLong()

    val particleShowDelayTicks: Long
        get() = editor.int("particle-delay", 10).toLong()

    val timeoutCheckIntervalTicks: Long
        get() = editor.int("timeout-check-interval", 1200).toLong()

    val timeoutCheckDelayTicks: Long
        get() = editor.int("timeout-check-delay", 10).toLong()

    val nearbyRadius: Double
        get() = editor.double("nearby-radius", 50.0)

    val particleType: org.bukkit.Particle
        get() = particle.particle("type", org.bukkit.Particle.END_ROD)

    val particleCount: Int
        get() = particle.int("count", 10)

    val particleExtra: Double
        get() = particle.double("extra", 0.0)

    val particleOffset: Double
        get() = particle.double("offset", 0.1)
}
