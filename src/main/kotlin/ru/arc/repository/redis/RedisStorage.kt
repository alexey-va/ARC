package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import ru.arc.network.RedisOperations
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import ru.arc.repository.Storage
import ru.arc.util.Logging
import java.lang.reflect.Type

/**
 * Redis implementation of Storage interface.
 */
class RedisStorage<T : Entity>(
    private val redis: RedisOperations,
    private val storageKey: String,
    private val entityType: Type,
    private val gson: Gson = Gson()
) : Storage<T> {

    @Suppress("UNCHECKED_CAST")
    override suspend fun load(id: String): RepoResult<T?> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val values = redis.loadMapEntries(storageKey, id).await()
            val json = values.getOrNull(0) ?: return@runCatching null
            gson.fromJson(json, entityType) as T?
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loadMany(ids: Set<String>): RepoResult<Map<String, T>> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val values = redis.loadMapEntries(storageKey, *ids.toTypedArray()).await()
            val result = mutableMapOf<String, T>()

            ids.forEachIndexed { index, id ->
                val json = values.getOrNull(index)
                if (json != null) {
                    try {
                        val entity = gson.fromJson(json, entityType) as T
                        result[id] = entity
                    } catch (e: Exception) {
                        Logging.warn("Failed to deserialize entity $id: ${e.message}")
                    }
                }
            }

            result
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun loadAll(): RepoResult<Map<String, T>> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val allData = redis.loadMap(storageKey).await()
            val result = mutableMapOf<String, T>()

            for ((id, json) in allData) {
                try {
                    val entity = gson.fromJson(json, entityType) as T
                    result[id] = entity
                } catch (e: Exception) {
                    Logging.warn("Failed to deserialize entity $id: ${e.message}")
                }
            }

            result
        }
    }

    override suspend fun save(entity: T): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val json = gson.toJson(entity)
            redis.saveMapEntries(storageKey, entity.id(), json).await()
            Unit
        }
    }

    override suspend fun saveMany(entities: Collection<T>): RepoResult<Unit> = withContext(Dispatchers.IO) {
        if (entities.isEmpty()) return@withContext RepoResult.success(Unit)

        RepoResult.runCatching {
            val keyValuePairs = entities.flatMap { entity ->
                listOf(entity.id(), gson.toJson(entity))
            }.toTypedArray()

            redis.saveMapEntries(storageKey, *keyValuePairs).await()
            Unit
        }
    }

    override suspend fun delete(id: String): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            // Save empty string to indicate deletion (null not allowed in varargs)
            redis.saveMapEntries(storageKey, id, "").await()
            Unit
        }
    }

    override suspend fun deleteMany(ids: Set<String>): RepoResult<Unit> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext RepoResult.success(Unit)

        RepoResult.runCatching {
            val keyValuePairs = ids.flatMap { id ->
                listOf(id, "")
            }.toTypedArray()

            redis.saveMapEntries(storageKey, *keyValuePairs).await()
            Unit
        }
    }

    override suspend fun exists(id: String): RepoResult<Boolean> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val values = redis.loadMapEntries(storageKey, id).await()
            val value = values.getOrNull(0)
            value != null && value.isNotEmpty()
        }
    }
}

