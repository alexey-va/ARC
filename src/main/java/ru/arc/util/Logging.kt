package ru.arc.util

import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager

object Logging {

    val config: Config = ConfigManager.of(ARC.plugin.dataPath, "logging.yml")
    val configVersion = ConfigManager.getVersion()
    var cachedLogLevel = getLogLevel()

    @JvmStatic
    fun info(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.INFO) {
            ARC.plugin.logger.log(
                java.util.logging.Level.INFO,
                formatPlaceholders(message, args)
            )
        }
    }

    @JvmStatic
    fun debug(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.DEBUG) {
            ARC.plugin.logger.log(
                java.util.logging.Level.FINE,
                formatPlaceholders(message, args)
            )
        }
    }

    @JvmStatic
    fun error(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.ERROR) {
            ARC.plugin.logger.log(
                java.util.logging.Level.SEVERE,
                formatPlaceholders(message, args)
            )
        }
    }

    @JvmStatic
    fun warn(message: String, vararg args: Any?) {
        if (getLogLevelCached() <= Level.WARN) {
            ARC.plugin.logger.log(
                java.util.logging.Level.WARNING,
                formatPlaceholders(message, args)
            )
        }
    }

    @JvmStatic
    fun setLogLevel(level: Level) {
        cachedLogLevel = level
    }

    fun formatPlaceholders(template: String, vararg args: Any?): String {
        // detect trailing throwable
        val throwable = args.lastOrNull() as? Throwable
        val effectiveArgs =
            if (throwable != null) args.dropLast(1).toTypedArray()
            else args

        val sb = StringBuilder()
        var argIndex = 0
        var i = 0
        while (i < template.length) {
            if (i + 1 < template.length && template[i] == '{' && template[i + 1] == '}') {
                sb.append(effectiveArgs.getOrNull(argIndex++) ?: "null")
                i += 2
            } else {
                sb.append(template[i])
                i++
            }
        }

        // append stacktrace if throwable present
        if (throwable != null) {
            sb.append(System.lineSeparator())
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            sb.append(sw.toString())
        }

        return sb.toString()
    }



    private fun getLogLevelCached(): Level {
        if (configVersion != ConfigManager.getVersion()) {
            val logLevel = getLogLevel()
            cachedLogLevel = logLevel
            return logLevel
        }
        return cachedLogLevel
    }

    private fun getLogLevel() =
        config.string("level")?.let {
            Level.valueOf(it.uppercase())
        } ?: Level.INFO


    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
