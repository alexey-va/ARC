package ru.arc.ops

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout

/**
 * Forwards Paper/Bukkit plugin load/enable errors into [OpsLogBuffer] for GET /ops/errors.
 */
object OpsStartupLogTap {
    private val patterns =
        listOf(
            "Could not load plugin",
            "Error occurred while enabling",
            "Error occurred while disabling",
        )

    private var appender: AbstractAppender? = null

    fun install() {
        if (appender != null) return
        val ctx = LogManager.getContext(false) as LoggerContext
        val layout =
            PatternLayout.newBuilder()
                .withPattern("%msg")
                .build()
        val tap =
            object : AbstractAppender("OpsStartupLogTap", null, layout, true, Property.EMPTY_ARRAY) {
                override fun append(event: LogEvent) {
                    val msg = event.message?.formattedMessage ?: return
                    if (!matches(msg)) return
                    OpsLogBuffer.append(event.level.toString(), plain(msg))
                }
            }
        tap.start()
        ctx.configuration.rootLogger.addAppender(tap, Level.WARN, null)
        ctx.updateLoggers()
        appender = tap
    }

    fun uninstall() {
        val tap = appender ?: return
        val ctx = LogManager.getContext(false) as LoggerContext
        tap.stop()
        ctx.configuration.rootLogger.removeAppender(tap.name)
        ctx.updateLoggers()
        appender = null
    }

    internal fun matches(message: String): Boolean =
        patterns.any { message.contains(it, ignoreCase = true) }

    private fun plain(text: String): String = text.replace(Regex("</?[^>]+>"), "")
}
