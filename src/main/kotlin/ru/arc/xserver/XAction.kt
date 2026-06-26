package ru.arc.xserver

import org.bukkit.Bukkit
import ru.arc.core.Tasks
import ru.arc.redis.gson.JsonSubtype
import ru.arc.redis.gson.JsonType
import ru.arc.util.Logging.error

@JsonType(
    property = "type",
    subtypes = [
        JsonSubtype(clazz = XMessage::class, name = "xmessage"),
        JsonSubtype(clazz = XCommand::class, name = "xcommand"),
        JsonSubtype(clazz = XPay::class, name = "xpay"),
        JsonSubtype(clazz = XRestart::class, name = "xrestart"),
        JsonSubtype(clazz = XRestartCancel::class, name = "xrestartcancel"),
    ],
)
abstract class XAction {

    var afterTimestamp: Long? = null
    var async: Boolean? = null

    protected abstract fun runInternal()

    fun run() {
        try {
            val ts = afterTimestamp ?: System.currentTimeMillis().also { afterTimestamp = it }
            val delta = ts - System.currentTimeMillis()
            val ticksDelay = maxOf(0L, delta / 50 + if (delta % 50 != 0L) 1 else 0)
            if (async == true) scheduleAsync(ticksDelay) else schedule(ticksDelay)
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

    private fun scheduleAsync(ticksDelay: Long) {
        Tasks.scheduler.runLaterAsync(ticksDelay) { runInternal() }
    }
}
