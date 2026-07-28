package ru.arc.ops

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager

/**
 * Configuration for the ops HTTP API ([OpsHttpModule]).
 */
open class OpsHttpConfig(private val config: Config) {

    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val token: String
        get() = config.string("token", "")

    open val bindHost: String
        get() = config.string("bind-host", "127.0.0.1")

    open val bindPort: Int
        get() = config.integer("bind-port", 25823)

    open val consoleEnabled: Boolean
        get() = config.bool("console-enabled", false)

    open val messagesEnabled: Boolean
        get() = config.bool("messages-enabled", true)

    open val effectsEnabled: Boolean
        get() = config.bool("effects-enabled", true)

    open val reloadEnabled: Boolean
        get() = config.bool("reload-enabled", true)

    open val runAsEnabled: Boolean
        get() = config.bool("run-as-enabled", false)

    open val itemsReadEnabled: Boolean
        get() = config.bool("items-read-enabled", true)

    open val itemsGiveEnabled: Boolean
        get() = config.bool("items-give-enabled", false)

    open val itemsGiveMaxStack: Int
        get() = config.integer("items-give-max-stack", 64).coerceIn(1, 6400)

    open val itemPresetsReadEnabled: Boolean
        get() = config.bool("item-presets-read-enabled", true)

    open val itemPresetsWriteEnabled: Boolean
        get() = config.bool("item-presets-write-enabled", false)

    open val cmiKitsWriteEnabled: Boolean
        get() = config.bool("cmi-kits-write-enabled", false)

    open val cmiHologramsReadEnabled: Boolean
        get() = config.bool("cmi-holograms-read-enabled", true)

    open val cmiHologramsWriteEnabled: Boolean
        get() = config.bool("cmi-holograms-write-enabled", false)

    open val scheduledCommandsReadEnabled: Boolean
        get() = config.bool("scheduled-commands-read-enabled", true)

    open val scheduledCommandsWriteEnabled: Boolean
        get() = config.bool("scheduled-commands-write-enabled", false)

    open val locationPoolsReadEnabled: Boolean
        get() = config.bool("location-pools-read-enabled", true)

    open val locationPoolsWriteEnabled: Boolean
        get() = config.bool("location-pools-write-enabled", false)

    open val treasurePoolsReadEnabled: Boolean
        get() = config.bool("treasure-pools-read-enabled", true)

    open val treasurePoolsWriteEnabled: Boolean
        get() = config.bool("treasure-pools-write-enabled", false)

    open val npcsReadEnabled: Boolean
        get() = config.bool("npcs-read-enabled", true)

    open val npcsWriteEnabled: Boolean
        get() = config.bool("npcs-write-enabled", false)

    open val errorBufferSize: Int
        get() = config.integer("error-buffer-size", 200).coerceIn(50, 2000)

    companion object {
        @Volatile
        private var instance: OpsHttpConfig = OpsHttpConfig(EmptyConfig)

        fun current(): OpsHttpConfig = instance

        fun reload() {
            val cfg = ConfigManager.ofModule(ARC.instance.dataPath, "ops-http.yml")
            instance = OpsHttpConfig(cfg)
            OpsLogBuffer.resize(instance.errorBufferSize)
        }

        fun loadForTest(test: OpsHttpConfig) {
            instance = test
            OpsLogBuffer.resize(test.errorBufferSize)
        }
    }
}

/** Test overrides without YAML. */
class TestOpsHttpConfig(
    override val enabled: Boolean = true,
    override val token: String = "test-token",
    override val bindHost: String = "127.0.0.1",
    override val bindPort: Int = 0,
    override val consoleEnabled: Boolean = false,
    override val messagesEnabled: Boolean = true,
    override val effectsEnabled: Boolean = true,
    override val reloadEnabled: Boolean = true,
    override val runAsEnabled: Boolean = false,
    override val itemsReadEnabled: Boolean = true,
    override val itemsGiveEnabled: Boolean = true,
    override val itemsGiveMaxStack: Int = 64,
    override val itemPresetsReadEnabled: Boolean = true,
    override val itemPresetsWriteEnabled: Boolean = false,
    override val cmiKitsWriteEnabled: Boolean = false,
    override val cmiHologramsReadEnabled: Boolean = true,
    override val cmiHologramsWriteEnabled: Boolean = false,
    override val scheduledCommandsReadEnabled: Boolean = true,
    override val scheduledCommandsWriteEnabled: Boolean = false,
    override val locationPoolsReadEnabled: Boolean = true,
    override val locationPoolsWriteEnabled: Boolean = false,
    override val treasurePoolsReadEnabled: Boolean = true,
    override val treasurePoolsWriteEnabled: Boolean = false,
    override val npcsReadEnabled: Boolean = true,
    override val npcsWriteEnabled: Boolean = false,
    override val errorBufferSize: Int = 100,
) : OpsHttpConfig(EmptyConfig) {
    fun copy(
        consoleEnabled: Boolean = this.consoleEnabled,
        messagesEnabled: Boolean = this.messagesEnabled,
        runAsEnabled: Boolean = this.runAsEnabled,
        itemsGiveEnabled: Boolean = this.itemsGiveEnabled,
        itemPresetsReadEnabled: Boolean = this.itemPresetsReadEnabled,
        itemPresetsWriteEnabled: Boolean = this.itemPresetsWriteEnabled,
        cmiKitsWriteEnabled: Boolean = this.cmiKitsWriteEnabled,
        cmiHologramsReadEnabled: Boolean = this.cmiHologramsReadEnabled,
        cmiHologramsWriteEnabled: Boolean = this.cmiHologramsWriteEnabled,
        scheduledCommandsReadEnabled: Boolean = this.scheduledCommandsReadEnabled,
        scheduledCommandsWriteEnabled: Boolean = this.scheduledCommandsWriteEnabled,
        locationPoolsReadEnabled: Boolean = this.locationPoolsReadEnabled,
        locationPoolsWriteEnabled: Boolean = this.locationPoolsWriteEnabled,
        treasurePoolsReadEnabled: Boolean = this.treasurePoolsReadEnabled,
        treasurePoolsWriteEnabled: Boolean = this.treasurePoolsWriteEnabled,
        npcsReadEnabled: Boolean = this.npcsReadEnabled,
        npcsWriteEnabled: Boolean = this.npcsWriteEnabled,
    ): TestOpsHttpConfig =
        TestOpsHttpConfig(
            enabled = enabled,
            token = token,
            bindHost = bindHost,
            bindPort = bindPort,
            consoleEnabled = consoleEnabled,
            messagesEnabled = messagesEnabled,
            effectsEnabled = effectsEnabled,
            reloadEnabled = reloadEnabled,
            runAsEnabled = runAsEnabled,
            itemsReadEnabled = itemsReadEnabled,
            itemsGiveEnabled = itemsGiveEnabled,
            itemsGiveMaxStack = itemsGiveMaxStack,
            itemPresetsReadEnabled = itemPresetsReadEnabled,
            itemPresetsWriteEnabled = itemPresetsWriteEnabled,
            cmiKitsWriteEnabled = cmiKitsWriteEnabled,
            cmiHologramsReadEnabled = cmiHologramsReadEnabled,
            cmiHologramsWriteEnabled = cmiHologramsWriteEnabled,
            scheduledCommandsReadEnabled = scheduledCommandsReadEnabled,
            scheduledCommandsWriteEnabled = scheduledCommandsWriteEnabled,
            locationPoolsReadEnabled = locationPoolsReadEnabled,
            locationPoolsWriteEnabled = locationPoolsWriteEnabled,
            treasurePoolsReadEnabled = treasurePoolsReadEnabled,
            treasurePoolsWriteEnabled = treasurePoolsWriteEnabled,
            npcsReadEnabled = npcsReadEnabled,
            npcsWriteEnabled = npcsWriteEnabled,
            errorBufferSize = errorBufferSize,
        )
}

private object EmptyConfig : Config(
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
    "empty-ops-http.yml",
)
