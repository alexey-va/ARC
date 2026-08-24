package ru.arc.xserver

import org.bukkit.Bukkit
import ru.arc.core.Tasks
import ru.arc.redis.gson.JsonSubtype
import ru.arc.redis.gson.JsonType
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn

@JsonType(
    property = "type",
    subtypes = [
        JsonSubtype(clazz = XMessage::class, name = "xmessage"),
        JsonSubtype(clazz = XCommand::class, name = "xcommand"),
        JsonSubtype(clazz = XRestart::class, name = "xrestart"),
        JsonSubtype(clazz = XRestartCancel::class, name = "xrestartcancel"),
    ],
)
abstract class XAction {

    var afterTimestamp: Long? = null
    /** Legacy wire field. Bukkit-backed actions are always scheduled on the main thread. */
    var async: Boolean? = null

    protected abstract fun runInternal()

    fun run() {
        try {
            val ts = afterTimestamp ?: System.currentTimeMillis().also { afterTimestamp = it }
            val delta = ts - System.currentTimeMillis()
            val ticksDelay = maxOf(0L, delta / 50 + if (delta % 50 != 0L) 1 else 0)
            if (async == true) {
                warn("Ignoring unsafe async=true on {}", javaClass.simpleName)
            }
            schedule(ticksDelay)
        } catch (e: Exception) {
            error("Error executing action: {}", this, e)
        }
    }

    private fun schedule(ticksDelay: Long) {
        if (ticksDelay == 0L && Bukkit.isPrimaryThread()) {
            runInternal()
            return
        }
        Tasks.scheduler.runLater(ticksDelay) { runInternal() }
    }

}

internal fun targetsCurrentServer(
    targetServers: Set<String>?,
    currentServer: String?,
): Boolean {
    if (targetServers == null) return true
    if (targetServers.isEmpty()) return false
    if (targetServers.any { it.trim().equals("all", ignoreCase = true) }) return true
    if (currentServer.isNullOrBlank()) return false
    return targetServers.any { it.trim().equals(currentServer.trim(), ignoreCase = true) }
}
