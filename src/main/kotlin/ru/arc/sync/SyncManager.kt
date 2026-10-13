package ru.arc.sync

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.sync.base.Sync
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

object SyncManager {

    private val syncMap: MutableMap<Class<*>, Sync> = ConcurrentHashMap()
    private val periodicSaveSyncs: MutableMap<UUID, PeriodicSaveSync> = ConcurrentHashMap()
    private val pendingSaveTasks = ConcurrentHashMap.newKeySet<ScheduledTask>()
    private var saveAllTask: ScheduledTask? = null

    fun registerSync(clazz: Class<*>, sync: Sync) {
        syncMap[clazz] = sync
    }

    /** Add an owner-scoped snapshot callback to the existing 60-tick sync round-robin. */
    fun registerPeriodicSave(owner: Plugin, snapshot: Consumer<Player>): AutoCloseable {
        check(Bukkit.isPrimaryThread()) { "Periodic player saves must be registered on the server thread" }
        val id = UUID.randomUUID()
        val sync = PeriodicSaveSync(owner, snapshot)
        periodicSaveSyncs[id] = sync
        return AutoCloseable {
            sync.unregister()
            periodicSaveSyncs.remove(id, sync)
        }
    }

    /** Keep joined player state across reloads; initialize only newly enabled syncs. */
    fun configureSync(
        clazz: Class<*>,
        enabled: Boolean,
        onlinePlayers: Collection<UUID>,
        create: () -> Sync?,
    ): Sync? {
        if (!enabled) {
            syncMap.remove(clazz)?.let { sync ->
                onlinePlayers.forEach(sync::forceSave)
                sync.shutdown()
            }
            return null
        }
        syncMap[clazz]?.let { return it }
        val sync = create() ?: return null
        registerSync(clazz, sync)
        onlinePlayers.forEach(sync::playerJoin)
        return sync
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
            val sync = SyncRoundRobin.getNext(roundRobinSyncs()) ?: return@repeating
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
        periodicSaveSyncs.values.forEach(PeriodicSaveSync::unregister)
        periodicSaveSyncs.clear()
        SyncRoundRobin.reset()
    }

    private fun roundRobinSyncs(): List<Sync> = syncMap.values.toList() + periodicSaveSyncs.values.toList()

    private class PeriodicSaveSync(
        private val owner: Plugin,
        private val snapshot: Consumer<Player>,
    ) : Sync {
        @Volatile private var registered = true

        override fun forceSave(uuid: UUID) {
            if (!registered || !owner.isEnabled) return
            val player = Bukkit.getPlayer(uuid) ?: return
            if (registered && owner.isEnabled && player.isOnline) snapshot.accept(player)
        }

        fun unregister() {
            registered = false
        }

        override fun shutdown() = unregister()
    }

    internal object SyncRoundRobin {
        private var previous: Sync? = null

        fun getNext(syncs: Collection<Sync>): Sync? {
            if (syncs.isEmpty()) return null
            val prev = previous
            if (prev == null) {
                previous = syncs.first()
                return previous
            }
            val ordered = syncs.toList()
            val previousIndex = ordered.indexOfFirst { it === prev }
            val nextIndex = if (previousIndex < 0) 0 else (previousIndex + 1) % ordered.size
            previous = ordered[nextIndex]
            return previous
        }

        fun reset() {
            previous = null
        }
    }
}
