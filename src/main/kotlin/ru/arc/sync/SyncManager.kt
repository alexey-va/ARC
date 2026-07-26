package ru.arc.sync

import org.bukkit.Bukkit
import org.bukkit.event.Event
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.sync.base.Sync
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SyncManager {

    private val syncMap: MutableMap<Class<*>, Sync> = ConcurrentHashMap()
    private val pendingSaveTasks = ConcurrentHashMap.newKeySet<ScheduledTask>()
    private var saveAllTask: ScheduledTask? = null

    fun registerSync(clazz: Class<*>, sync: Sync) {
        syncMap[clazz] = sync
    }

    @Suppress("unused")
    fun unregisterSync(clazz: Class<*>) {
        syncMap.remove(clazz)
    }

    @Suppress("unused")
    fun getSyncs(): List<Sync> = syncMap.values.toList()

    @Suppress("unused")
    fun getSync(clazz: Class<*>): Sync? = syncMap[clazz]

    @JvmStatic
    fun processEvent(event: Event) {
        syncMap.values.forEach { it.processEvent(event) }
    }

    @JvmStatic
    fun playerJoin(uuid: UUID) {
        syncMap.values.forEach { it.playerJoin(uuid) }
    }

    @JvmStatic
    fun playerQuit(uuid: UUID) {
        syncMap.values.forEach { it.playerQuit(uuid) }
    }

    fun startSaveAllTasks() {
        stopSaveAllTasks()
        saveAllTask = repeating(60.ticks, delay = 60.ticks) {
            val sync = SyncRoundRobin.getNext(syncMap.values) ?: return@repeating
            val players = Bukkit.getOnlinePlayers().toList()
            players.forEachIndexed { index, player ->
                lateinit var pendingTask: ScheduledTask
                pendingTask =
                    delayed((index + 1).ticks) {
                        try {
                            if (player.isOnline) sync.forceSave(player.uniqueId)
                        } finally {
                            pendingSaveTasks.remove(pendingTask)
                        }
                    }
                pendingSaveTasks.add(pendingTask)
                if (pendingTask.isCancelled) {
                    pendingSaveTasks.remove(pendingTask)
                }
            }
        }
    }

    fun saveAll() {
        val players = Bukkit.getOnlinePlayers().toList()
        syncMap.values.forEach { sync ->
            players.forEach { player ->
                try {
                    sync.forceSave(player.uniqueId)
                } catch (exception: Exception) {
                    error(
                        "Failed to save {} data for {}",
                        sync.javaClass.simpleName,
                        player.uniqueId,
                        exception,
                    )
                }
            }
        }
    }

    fun stopSaveAllTasks() {
        saveAllTask?.let { if (!it.isCancelled) it.cancel() }
        saveAllTask = null
        pendingSaveTasks.forEach { task ->
            if (!task.isCancelled) task.cancel()
        }
        pendingSaveTasks.clear()
    }

    fun shutdown(save: Boolean = true) {
        stopSaveAllTasks()
        if (save) {
            saveAll()
        }
        syncMap.values.forEach { sync ->
            try {
                sync.shutdown()
            } catch (exception: Exception) {
                error("Failed to shut down sync {}", sync.javaClass.simpleName, exception)
            }
        }
        syncMap.clear()
        SyncRoundRobin.reset()
    }

    private object SyncRoundRobin {
        private var previous: Sync? = null

        fun getNext(syncs: Collection<Sync>): Sync? {
            if (syncs.isEmpty()) return null
            val prev = previous
            if (prev == null) {
                previous = syncs.first()
                return previous
            }
            for (sync in syncs) {
                if (sync === prev) continue
                previous = sync
                return sync
            }
            previous = syncs.first()
            return previous
        }

        fun reset() {
            previous = null
        }
    }
}
