package ru.arc.util

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager

object Logging {

    val config: Config by lazy {
        if (ARC.plugin == null) {
            // Return a dummy config for testing
            ConfigManager.create(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")),
                "logging.yml",
                "test-logging"
            )
        } else {
            ConfigManager.of(ARC.plugin.dataPath, "logging.yml")
        }
    }
    var configVersion = -1
    var initializing = false
    var cachedLogLevel = Level.INFO

    @JvmStatic
    fun info(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.INFO) {
            val logger = ARC.plugin?.logger ?: java.util.logging.Logger.getLogger("ARC")
            logger.log(
                java.util.logging.Level.INFO,
                format(message, *args)
            )
        }
    }

    @JvmStatic
    fun debug(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.DEBUG) {
            val logger = ARC.plugin?.logger ?: java.util.logging.Logger.getLogger("ARC")
            logger.log(
                java.util.logging.Level.FINE,
                format(message, *args)
            )
        }
    }

    @JvmStatic
    fun error(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.ERROR) {
            val logger = ARC.plugin?.logger ?: java.util.logging.Logger.getLogger("ARC")
            logger.log(
                java.util.logging.Level.SEVERE,
                format(message, *args)
            )
        }
    }

    @JvmStatic
    fun warn(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.WARN) {
            val logger = ARC.plugin?.logger ?: java.util.logging.Logger.getLogger("ARC")
            logger.log(
                java.util.logging.Level.WARNING,
                format(message, *args)
            )
        }
    }

    @JvmStatic
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

    private fun render(v: Any?): String = when (v) {
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
        is Iterable<*> -> v.joinToString(prefix = "[", postfix = "]") { render(it) }
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

    // ---------- level resolution with reentrancy guard ----------
    private fun getLogLevelCached(): Level {
        val version = ConfigManager.getVersion()
        val current = cachedLogLevel
        if (!initializing && version != configVersion) {
            // защитим от рекурсии, если Config.* внутри дернет Logging.debug()
            initializing = true
            try {
                val lvl = safeResolveLevel()
                cachedLogLevel = lvl
                configVersion = version
                return lvl
            } catch (_: Throwable) {
                // фолбэк
                cachedLogLevel = Level.INFO
                configVersion = version
                return Level.INFO
            } finally {
                initializing = false
            }
        }
        return current
    }

    // читать из конфига без логов
    private fun safeResolveLevel(): Level {
        val raw = try {
            config.string("level", "INFO")
        } catch (_: Throwable) {
            "INFO"
        }
        return try {
            Level.valueOf(raw?.uppercase() ?: "INFO")
        } catch (_: Throwable) {
            Level.INFO
        }
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
