package ru.arc.ops

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager

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
    override val errorBufferSize: Int = 100,
) : OpsHttpConfig(EmptyConfig) {
    fun copy(
        consoleEnabled: Boolean = this.consoleEnabled,
        messagesEnabled: Boolean = this.messagesEnabled,
        runAsEnabled: Boolean = this.runAsEnabled,
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
            errorBufferSize = errorBufferSize,
        )
}

private object EmptyConfig : Config(
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
    "empty-ops-http.yml",
)
