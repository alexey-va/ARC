package ru.arc.util

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import ru.arc.logging.ArcLogging
import ru.arc.logging.LogLevel
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LoggingOpsSink
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.ops.OpsLogBuffer
import java.nio.file.Paths

/**
 * Paper plugin logging facade — delegates to [ArcLogging] with [PaperLoggingPlatform].
 *
 * Do not duplicate this type in arc-core (classpath conflict with legacy ARC).
 */
object Logging {
    private const val LOKI_LOGGER_PREFIX = "ru.arc"

    private val paperPlatform = PaperLoggingPlatform(brandTag = "ARC")

    var quietMode: Boolean
        get() = ArcLogging.quietMode
        set(value) {
            ArcLogging.quietMode = value
        }

    var disableLokiAppender: Boolean
        get() = ArcLogging.disableLoki
        set(value) {
            ArcLogging.disableLoki = value
        }

    val config: Config by lazy {
        if (ARC.plugin == null) {
            ConfigManager.create(
                Paths.get(System.getProperty("java.io.tmpdir")),
                LoggingModuleConfig.bundledResourcePath(),
                "test-logging",
            )
        } else {
            ConfigManager.ofModule(ARC.instance.dataPath, "logging.yml")
        }
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        ;

        internal fun toCore(): LogLevel = LogLevel.valueOf(name)

        companion object {
            fun from(core: LogLevel): Level = valueOf(core.name)
        }
    }

    @JvmStatic
    fun bootstrapForTests(config: Config = EmptyConfig) {
        ArcLogging.install(
            platform = paperPlatform,
            configSource =
                LoggingConfigSource {
                    config
                },
        )
    }

    @JvmStatic
    fun installForPlugin(dataPath: java.nio.file.Path) {
        val configSource =
            object : LoggingConfigSource {
                override fun config(): Config = ConfigManager.ofModule(dataPath, "logging.yml")

                override fun configVersion(): Int = ConfigManager.getVersion()
            }
        ArcLogging.install(
            platform = paperPlatform,
            configSource = configSource,
            loki =
                LokiInstallSpec(
                    dataFolder = dataPath,
                    target = LokiAttachTarget.LOGGER_PREFIX,
                    loggerPrefix = LOKI_LOGGER_PREFIX,
                ),
            opsSink = LoggingOpsSink { level, plain -> OpsLogBuffer.append(level, plain) },
        )
    }

    @JvmStatic
    fun escapeMM(s: String): String = ArcLogging.escapeMM(s)

    @JvmStatic
    @JvmOverloads
    fun withContext(
        module: String? = null,
        player: String? = null,
        action: String? = null,
        block: Runnable,
    ) = ArcLogging.withContext(module, player, action, block)

    @JvmStatic
    fun consoleLog(miniMessage: String) {
        paperPlatform.writeRaw(miniMessage)
    }

    @JvmStatic
    fun info(
        message: String,
        vararg args: Any?,
    ) = ArcLogging.info(message, *args)

    @JvmStatic
    fun debug(
        message: String,
        vararg args: Any?,
    ) = ArcLogging.debug(message, *args)

    @JvmStatic
    fun warn(
        message: String,
        vararg args: Any?,
    ) = ArcLogging.warn(message, *args)

    @JvmStatic
    fun error(
        message: String,
        vararg args: Any?,
    ) = ArcLogging.error(message, *args)

    internal fun matchesQuietSource(
        className: String,
        sources: Collection<String>,
    ): Boolean = ArcLogging.matchesQuietSource(className, sources)

    internal fun plainForBuffer(text: String): String = ArcLogging.plainForBuffer(text)

    @JvmStatic
    fun getLogLevel(): Level = Level.from(ArcLogging.getLogLevel())

    fun setLogLevel(level: Level) {
        ArcLogging.setLogLevel(level.toCore())
    }

    @JvmStatic
    fun format(
        template: String,
        vararg args: Any?,
    ): String = ArcLogging.format(template, *args)

    internal fun resolveCallerLogger() = ArcLogging.resolveCallerLogger()

    internal fun shouldSkipStructuredCallerFrame(className: String) =
        ru.arc.logging.QuietDebugFilter.shouldSkipStructuredCallerFrame(className)

    @JvmStatic
    fun addLokiAppender() {
        if (disableLokiAppender || ARC.plugin == null) return
        ArcLogging.installLoki(
            LokiInstallSpec(
                dataFolder = ARC.instance.dataPath,
                target = LokiAttachTarget.LOGGER_PREFIX,
                loggerPrefix = LOKI_LOGGER_PREFIX,
            ),
        )
    }

    internal fun buildLokiLayout(
        cfg: Config,
        configuration: org.apache.logging.log4j.core.config.Configuration,
    ) = ArcLogging.buildLokiLayout(cfg, configuration)

    internal fun resolveLokiLevel(cfg: Config) = ArcLogging.resolveLokiLevel(cfg)
}
