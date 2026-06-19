package ru.arc.sync

import org.bukkit.Bukkit
import org.bukkit.event.Event
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.sync.base.Sync
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SyncManager {

    private val syncMap: MutableMap<Class<*>, Sync> = ConcurrentHashMap()
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
                delayed((index + 1).ticks) {
                    if (player.isOnline) sync.forceSave(player.uniqueId)
                }
            }
        }
    }

    fun saveAll() {
        val players = Bukkit.getOnlinePlayers().toList()
        syncMap.values.forEach { sync ->
            players.forEach { player -> sync.forceSave(player.uniqueId) }
        }
    }

    fun stopSaveAllTasks() {
        saveAllTask?.let { if (!it.isCancelled) it.cancel() }
        saveAllTask = null
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
    }
}
