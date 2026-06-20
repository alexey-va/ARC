package ru.arc.sync.base

import org.bukkit.event.Event
import ru.arc.util.Logging.debug
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

class ActionCanceller {

    val cancellers: MutableMap<Class<out Event>, (Event) -> Unit> = ConcurrentHashMap()
    val preventUntil: MutableMap<UUID, Long> = ConcurrentHashMap()
    var pruneTask: Future<*>? = null

    fun <T : Event> registerCanceller(clazz: Class<T>, canceller: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        cancellers[clazz] = canceller as (Event) -> Unit
    }

    fun checkAndCancel(playerUuid: UUID, event: Event): Boolean {
        val until = preventUntil[playerUuid]
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                debug("Prevented event {} for {}", event.javaClass.simpleName, playerUuid)
                debug("Prevented until {}", until)
                debug("Current time {}", System.currentTimeMillis())
                val canceller = cancellers[event.javaClass]
                if (canceller != null) {
                    canceller(event)
                    return true
                }
                return false
            } else {
                preventUntil.remove(playerUuid)
            }
        }
        return false
    }

    fun addTemporaryProtection(uuid: UUID, ms: Long) {
        preventUntil[uuid] = System.currentTimeMillis() + ms
    }

    fun stopTasks() {
        pruneTask?.takeIf { !it.isCancelled }?.cancel(true)
    }
}
