package ru.arc.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.arc.network.ChannelListener
import ru.arc.network.RedisOperations
import ru.arc.network.repos.RepoData
import ru.arc.util.Common
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Legacy Redis repository wrapper for backward compatibility.
 * This provides the old API using the RedisOperations interface.
 */
class LegacyRedisRepo<T : RepoData<T>> private constructor(
    private val id: String,
    private val clazz: Class<T>,
    private val storageKey: String,
    private val updateChannel: String,
    private val redisManager: RedisOperations,
    private val saveInterval: Long,
    private val loadAll: Boolean,
    private val backupFolder: Path?,
    private val saveBackups: Boolean,
    private val onUpdate: Consumer<T>?,
) {
    private val cache = ConcurrentHashMap<String, T>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private var initialized = false

    private val channelListener =
        ChannelListener { _, message, _ ->
            try {
                val entity = Common.gson.fromJson(message, clazz)
                if (entity != null) {
                    val existing = cache[entity.id()]
                    if (existing != null) {
                        existing.merge(entity)
                    } else {
                        cache[entity.id()] = entity
                    }
                    onUpdate?.accept(entity)
                }
            } catch (_: Exception) {
                // Ignore parse errors
            }
        }

    init {
        initialize()
    }

    private fun initialize() {
        if (initialized) return

        // Load all data if requested
        if (loadAll) {
            redisManager.loadMap(storageKey).thenAccept { allData ->
                allData.forEach { (key, json) ->
                    try {
                        val entity = Common.gson.fromJson(json, clazz)
                        if (entity != null) {
                            cache[key] = entity
                        }
                    } catch (_: Exception) {
                        // Skip invalid entries
                    }
                }
            }
        }

        // Subscribe to updates
        redisManager.registerChannelUnique(updateChannel, channelListener)

        // Start save task
        saveJob =
            scope.launch {
                while (isActive) {
                    delay(saveInterval * 50) // Convert ticks to millis
                    saveDirty()
                }
            }

        initialized = true
    }

    fun getOrNull(id: String): CompletableFuture<T?> {
        val cached = cache[id]
        if (cached != null) {
            return CompletableFuture.completedFuture(cached)
        }

        return redisManager.loadMapEntries(storageKey, id).thenApply { results ->
            val json = results.firstOrNull()
            if (json != null) {
                try {
                    val entity = Common.gson.fromJson(json, clazz)
                    if (entity != null) {
                        cache[id] = entity
                    }
                    entity
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    fun getOrCreate(
        id: String,
        factory: () -> T,
    ): CompletableFuture<T> {
        val cached = cache[id]
        if (cached != null) {
            return CompletableFuture.completedFuture(cached)
        }

        return redisManager.loadMapEntries(storageKey, id).thenApply { results ->
            val json = results.firstOrNull()
            if (json != null) {
                try {
                    val entity = Common.gson.fromJson(json, clazz)
                    if (entity != null) {
                        cache[id] = entity
                        return@thenApply entity
                    }
                } catch (_: Exception) {
                    // Fall through to create new
                }
            }

            val newEntity = factory()
            cache[id] = newEntity
            newEntity.isDirty = true
            newEntity
        }
    }

    fun getNow(id: String): T? = cache[id]

    fun create(entity: T) {
        cache[entity.id()] = entity
        entity.isDirty = true
    }

    fun delete(entity: T) {
        cache.remove(entity.id())
        // Delete from Redis: pass key, null value to delete
        redisManager.saveMapEntries(storageKey, entity.id(), null)
    }

    fun all(): Collection<T> = cache.values.toList()

    fun addContext(id: String) {
        // Load entity into context if not present
        if (!cache.containsKey(id)) {
            getOrNull(id)
        }
    }

    fun removeContext(id: String) {
        val entity = cache[id]
        if (entity != null && entity.isDirty) {
            saveEntity(entity)
        }
        cache.remove(id)
    }

    fun forceSave() {
        saveDirty()
    }

    private fun saveDirty() {
        val dirtyEntities = cache.values.filter { it.isDirty }
        dirtyEntities.forEach { entity ->
            saveEntity(entity)
            entity.isDirty = false
        }

        // Clean up expired entries
        val toRemove = cache.values.filter { it.isRemove }
        toRemove.forEach { entity ->
            cache.remove(entity.id())
            // Delete from Redis
            redisManager.saveMapEntries(storageKey, entity.id(), null)
        }
    }

    private fun saveEntity(entity: T) {
        val json = Common.gson.toJson(entity)
        redisManager.saveMapEntries(storageKey, entity.id(), json)
        redisManager.publish(updateChannel, json)
    }

    fun shutdown() {
        saveDirty()
        saveJob?.cancel()
        redisManager.unregisterChannel(updateChannel, channelListener)
        scope.cancel()
    }

    companion object {
        @JvmStatic
        fun <T : RepoData<T>> builder(clazz: Class<T>): Builder<T> = Builder(clazz)
    }

    class Builder<T : RepoData<T>>(
        private val clazz: Class<T>,
    ) {
        private var id: String = "default"
        private var storageKey: String = "default"
        private var updateChannel: String = "default_update"
        private var redisManager: RedisOperations? = null
        private var saveInterval: Long = 20L
        private var loadAll: Boolean = false
        private var backupFolder: Path? = null
        private var saveBackups: Boolean = false
        private var onUpdate: Consumer<T>? = null

        fun id(id: String) = apply { this.id = id }

        fun clazz(clazz: Class<T>) = this // Already set in constructor

        fun storageKey(key: String) = apply { this.storageKey = key }

        fun updateChannel(channel: String) = apply { this.updateChannel = channel }

        fun redisManager(manager: RedisOperations) = apply { this.redisManager = manager }

        fun saveInterval(interval: Long) = apply { this.saveInterval = interval }

        fun loadAll(load: Boolean) = apply { this.loadAll = load }

        fun backupFolder(folder: Path) = apply { this.backupFolder = folder }

        fun saveBackups(save: Boolean) = apply { this.saveBackups = save }

        fun onUpdate(consumer: Consumer<T>) = apply { this.onUpdate = consumer }

        fun build(): LegacyRedisRepo<T> {
            requireNotNull(redisManager) { "RedisManager must be set" }
            return LegacyRedisRepo(
                id = id,
                clazz = clazz,
                storageKey = storageKey,
                updateChannel = updateChannel,
                redisManager = redisManager!!,
                saveInterval = saveInterval,
                loadAll = loadAll,
                backupFolder = backupFolder,
                saveBackups = saveBackups,
                onUpdate = onUpdate,
            )
        }
    }
}
