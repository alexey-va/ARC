package ru.arc.util

import net.kyori.adventure.text.minimessage.MiniMessage
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.filter.BurstFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.bukkit.Bukkit
import pl.tkowalcz.tjahzi.log4j2.LokiAppender
import pl.tkowalcz.tjahzi.log4j2.labels.Label
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.ops.OpsLogBuffer
import java.nio.charset.StandardCharsets

object Logging {

    private const val LOKI_LOGGER_PREFIX = "ru.arc"

    /**
     * When true, suppresses INFO and DEBUG logs. Useful for tests.
     * Set this before plugin loads to reduce console spam.
     */
    @JvmField
    var quietMode = false

    /**
     * When true, skips Loki appender initialization. Set in tests.
     */
    @JvmField
    var disableLokiAppender = false

    val config: Config by lazy {
        if (ARC.plugin == null) {
            // Return a dummy config for testing
            ConfigManager.create(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                ConfigManager.bundledModuleResource("logging.yml"),
                "test-logging",
            )
        } else {
            ConfigManager.ofModule(ARC.instance.dataPath, "logging.yml")
        }
    }
    var configVersion = -1
    var initializing = false
    var cachedLogLevel = Level.INFO
    private var quietSourcesConfigVersion = -1
    private var cachedQuietSources: Set<String> = emptySet()

    private val mm = MiniMessage.miniMessage()

    /** Escape user-supplied text so it isn't parsed as MiniMessage tags. */
    fun escapeMM(s: String): String = s.replace("\\", "\\\\").replace("<", "\\<")

    /**
     * Run a block with optional structured fields attached to Loki JSON (Log4j MDC).
     */
    @JvmStatic
    @JvmOverloads
    fun withContext(
        module: String? = null,
        player: String? = null,
        action: String? = null,
        block: Runnable,
    ) {
        val keys =
            listOfNotNull(
                module?.let { "module" to it },
                player?.let { "player" to it },
                action?.let { "action" to it },
            )
        keys.forEach { (key, value) -> ThreadContext.put(key, value) }
        try {
            block.run()
        } finally {
            keys.forEach { (key, _) -> ThreadContext.remove(key) }
        }
    }

    /**
     * Send a MiniMessage-formatted string to the server console.
     * Paper's ConsoleSender renders Adventure components with ANSI colors.
     * Falls back to a plain JUL logger if Bukkit is not available (unit tests).
     */
    @JvmStatic
    fun consoleLog(miniMessage: String) {
        try {
            val component = mm.deserialize(miniMessage)
            Bukkit.getConsoleSender().sendMessage(component)
        } catch (_: Exception) {
            val plain = miniMessage.replace(Regex("</?[^>]+>"), "")
            java.util.logging.Logger.getLogger("ARC").info(plain)
        }
    }

    @JvmStatic
    fun info(message: String, vararg args: Any?) {
        if (quietMode) return
        if (getLogLevelCached() <= Level.INFO) {
            val text = format(message, *args)
            emitStructured(Level.INFO, plainForBuffer(text), extractThrowable(*args))
            consoleLog("<dark_gray>[ARC]</dark_gray> ${format(message, *args)}")
        }
    }

    @JvmStatic
    fun debug(message: String, vararg args: Any?) {
        if (quietMode) return
        if (getLogLevelCached() <= Level.DEBUG) {
            if (isQuietDebugCaller()) return
            val text = format(message, *args)
            emitStructured(Level.DEBUG, plainForBuffer(text), extractThrowable(*args))
            consoleLog("<dark_gray>[ARC] [DEBUG]</dark_gray> <gray>${format(message, *args)}</gray>")
        }
    }

    /** True if the direct DEBUG caller matches any [quietDebugSources] prefix. */
    internal fun matchesQuietSource(className: String, sources: Collection<String>): Boolean {
        if (sources.isEmpty()) return false
        return sources.any { prefix ->
            val p = prefix.trim()
            p.isNotEmpty() && (className == p || className.startsWith("$p."))
        }
    }

    internal fun quietDebugSources(): Set<String> {
        val version = ConfigManager.getVersion()
        if (version != quietSourcesConfigVersion) {
            cachedQuietSources =
                runCatching { config.stringList("debug-quiet-sources") }
                    .getOrElse { emptyList() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            quietSourcesConfigVersion = version
        }
        return cachedQuietSources
    }

    private fun shouldSkipQuietDebugStackFrame(className: String): Boolean =
        className.startsWith("ru.arc.util.Logging") ||
            className.startsWith("java.lang.Thread") ||
            className.startsWith("jdk.internal") ||
            className.startsWith("java.util.concurrent") ||
            className.startsWith("kotlinx.coroutines") ||
            className.startsWith("kotlin.coroutines") ||
            className.startsWith("sun.reflect") ||
            className.startsWith("java.lang.reflect")

    private fun isQuietDebugCaller(): Boolean {
        val sources = quietDebugSources()
        if (sources.isEmpty()) return false
        return Thread.currentThread().stackTrace
            .asSequence()
            .map { it.className }
            .filterNot { shouldSkipQuietDebugStackFrame(it) }
            .any { matchesQuietSource(it, sources) }
    }

    internal fun plainForBuffer(text: String): String = text.replace(Regex("</?[^>]+>"), "")

    @JvmStatic
    fun error(message: String, vararg args: Any?) {
        if (quietMode) return
        if (getLogLevelCached() <= Level.ERROR) {
            val text = format(message, *args)
            val throwable = extractThrowable(*args)
            emitStructured(Level.ERROR, plainForBuffer(text), throwable)
            OpsLogBuffer.append("ERROR", plainForBuffer(text))
            consoleLog("<dark_gray>[ARC]</dark_gray> <red><bold>[ERROR]</bold></red> $text")
            val julLogger = ARC.plugin?.logger ?: java.util.logging.Logger.getLogger("ARC")
            if (throwable != null) {
                julLogger.log(java.util.logging.Level.SEVERE, text, throwable)
            } else {
                julLogger.log(java.util.logging.Level.SEVERE, text)
            }
        }
    }

    @JvmStatic
    fun warn(message: String, vararg args: Any?) {
        if (quietMode) return
        if (getLogLevelCached() <= Level.WARN) {
            val text = format(message, *args)
            emitStructured(Level.WARN, plainForBuffer(text), extractThrowable(*args))
            OpsLogBuffer.append("WARN", plainForBuffer(text))
            consoleLog("<dark_gray>[ARC]</dark_gray> <yellow><bold>[WARN]</bold></yellow> $text")
        }
    }

    @JvmStatic
    fun getLogLevel(): Level = getLogLevelCached()

    fun setLogLevel(level: Level) {
        cachedLogLevel = level
    }

    fun format(template: String, vararg args: Any?): String {
        val nonThrow = ArrayList<Any?>(args.size)
        val throws = ArrayList<Throwable>()
        for (a in args) if (a is Throwable) throws += a else nonThrow += a

        val main = substitute(template, nonThrow.map { render(it) }.toTypedArray())
        if (throws.isEmpty()) return main

        val sb = StringBuilder(main.length + 256)
        sb.append(main).append(System.lineSeparator()).append("--- exceptions ---").append(System.lineSeparator())
        throws.forEachIndexed { i, t ->
            sb.append('#').append(i + 1).append(' ')
                .append(t::class.java.name).append(": ").append(t.message ?: "")
                .append(System.lineSeparator())
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            sb.append(sw.toString())
        }
        return sb.toString()
    }

    private fun substitute(template: String, args: Array<String>): String {
        val sb = StringBuilder(template.length + 64)
        var i = 0
        var ai = 0
        while (i < template.length) {
            if (i + 1 < template.length && template[i] == '{' && template[i + 1] == '}') {
                sb.append(if (ai < args.size) args[ai++] else "{}"); i += 2
            } else {
                sb.append(template[i++])
            }
        }
        return sb.toString()
    }

    private fun render(v: Any?): String =
        when (v) {
            null -> "null"
            is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
            is ByteArray -> v.joinToString(prefix = "[", postfix = "]")
            is ShortArray -> v.joinToString(prefix = "[", postfix = "]")
            is IntArray -> v.joinToString(prefix = "[", postfix = "]")
            is LongArray -> v.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> v.joinToString(prefix = "[", postfix = "]")
            is CharArray -> v.joinToString(prefix = "[", postfix = "]")
            is Array<*> -> v.contentDeepToString()
            is Collection<*> -> v.joinToString(prefix = "[", postfix = "]") { render(it) }
            is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { "${render(it.key)}=${render(it.value)}" }
            else -> if (v.javaClass.isArray) reflectArray(v) else v.toString()
        }

    private fun reflectArray(arr: Any): String {
        val n = java.lang.reflect.Array.getLength(arr)
        return buildString {
            append('[')
            for (i in 0 until n) {
                if (i > 0) append(", ")
                append(render(java.lang.reflect.Array.get(arr, i)))
            }
            append(']')
        }
    }

    private fun extractThrowable(vararg args: Any?): Throwable? = args.firstOrNull { it is Throwable } as? Throwable

    private fun emitStructured(level: Level, plainMessage: String, throwable: Throwable?) {
        if (disableLokiAppender) return
        val logger = resolveCallerLogger()
        val log4jLevel = toLog4jLevel(level)
        if (throwable != null) {
            logger.log(log4jLevel, plainMessage, throwable)
        } else {
            logger.log(log4jLevel, plainMessage)
        }
    }

    internal fun resolveCallerLogger(): org.apache.logging.log4j.Logger {
        val caller =
            Thread.currentThread().stackTrace
                .asSequence()
                .map { it.className }
                .firstOrNull { !shouldSkipStructuredCallerFrame(it) }
                ?: LOKI_LOGGER_PREFIX
        return LogManager.getLogger(caller)
    }

    internal fun shouldSkipStructuredCallerFrame(className: String): Boolean =
        shouldSkipQuietDebugStackFrame(className) ||
            className.startsWith("java.lang.invoke") ||
            className.startsWith("kotlin.jvm") ||
            className.startsWith("kotlin.coroutines.jvm")

    internal fun toLog4jLevel(level: Level): org.apache.logging.log4j.Level =
        when (level) {
            Level.DEBUG -> org.apache.logging.log4j.Level.DEBUG
            Level.INFO -> org.apache.logging.log4j.Level.INFO
            Level.WARN -> org.apache.logging.log4j.Level.WARN
            Level.ERROR -> org.apache.logging.log4j.Level.ERROR
        }

    // ---------- level resolution with reentrancy guard ----------
    private fun getLogLevelCached(): Level {
        val version = ConfigManager.getVersion()
        val current = cachedLogLevel
        if (!initializing && version != configVersion) {
            initializing = true
            try {
                val lvl = safeResolveLevel()
                cachedLogLevel = lvl
                configVersion = version
                return lvl
            } catch (_: Throwable) {
                cachedLogLevel = Level.INFO
                configVersion = version
                return Level.INFO
            } finally {
                initializing = false
            }
        }
        return current
    }

    private fun safeResolveLevel(): Level {
        val raw =
            try {
                config.string("level", "INFO")
            } catch (_: Throwable) {
                "INFO"
            }
        return try {
            Level.valueOf(raw.uppercase())
        } catch (_: Throwable) {
            Level.INFO
        }
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    // ==================== Loki Appender ====================

    /**
     * Adds Loki appender to Log4j for centralized logging.
     * Call this during plugin initialization.
     */
    @JvmStatic
    fun addLokiAppender() {
        if (disableLokiAppender) return

        try {
            val cfg =
                if (ARC.plugin != null) {
                    ConfigManager.ofModule(ARC.instance.dataPath, "logging.yml")
                } else {
                    return
                }

            if (!cfg.bool("enabled", false)) return

            val labelsMap = cfg.map<String>("labels", emptyMap())
            val labels =
                labelsMap.entries
                    .filter { (key, _) ->
                        when {
                            !Label.hasValidName(key) -> {
                                warn("Invalid label name: {}", key)
                                false
                            }

                            else -> true
                        }
                    }.map { (key, value) -> Label.createLabel(key, value, null) }
                    .toTypedArray()

            val rootLogger = LogManager.getRootLogger() as Logger
            val configuration = rootLogger.context.configuration
            val layout = buildLokiLayout(cfg, configuration)
            val lokiLevel = resolveLokiLevel(cfg)

            val filter =
                BurstFilter.newBuilder()
                    .setLevel(lokiLevel)
                    .setRate(cfg.integer("rate", 20).toFloat())
                    .setMaxBurst(cfg.integer("maxBurst", 100).toLong())
                    .build()

            val appender =
                LokiAppender.newBuilder().apply {
                    host = cfg.string("host", "localhost")
                    port = cfg.integer("port", 3100)
                    setLabels(labels)
                    setHeaders(arrayOf())
                    setMetadata(arrayOf())
                    name = "ArcLokiAppender"
                    setLayout(layout)
                    setFilter(filter)
                }.build()

            appender.start()
            configuration.addAppender(appender)

            val loggerConfig = configuration.getLoggerConfig(LOKI_LOGGER_PREFIX)
            loggerConfig.addAppender(appender, lokiLevel, null)
            loggerConfig.level = lokiLevel
            loggerConfig.isAdditive = false
            rootLogger.context.updateLoggers()
        } catch (e: Throwable) {
            warn("Failed to add Loki appender: {}", e)
        }
    }

    internal fun buildLokiLayout(
        cfg: Config,
        configuration: org.apache.logging.log4j.core.config.Configuration,
    ): org.apache.logging.log4j.core.Layout<String> {
        val format = cfg.string("loki-format", "json").lowercase()
        if (format == "pattern") {
            return PatternLayout.newBuilder()
                .withPattern("%d{ISO8601} [%t] %-5level %logger{36} - %msg%n")
                .withCharset(StandardCharsets.UTF_8)
                .build()
        }

        return ArcJsonLayout.create(configuration)
    }

    internal fun resolveLokiLevel(cfg: Config): org.apache.logging.log4j.Level {
        val raw = cfg.string("loki-level", "INFO")
        return runCatching { org.apache.logging.log4j.Level.valueOf(raw.uppercase()) }
            .getOrDefault(org.apache.logging.log4j.Level.INFO)
    }
}
