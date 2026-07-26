package ru.arc.sync.base

import ru.arc.ARC
import ru.arc.core.sync
import ru.arc.redis.RedisOperations
import ru.arc.util.Common
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SyncRepo<T : SyncData>(
    private val clazz: Class<T>,
    private val key: String,
    private val redisManager: RedisOperations,
    private val dataApplier: (T) -> Unit,
    private val dataProducer: (Context) -> T?,
) {
    init {
        require(key.isNotBlank()) { "Sync repository key must not be blank" }
    }

    private fun saveDataPersistently(data: T): CompletableFuture<Void> {
        val uuid = data.uuid()?.toString() ?: return CompletableFuture.completedFuture(null)
        val json =
            try {
                Common.gson.toJson(data)
            } catch (exception: Exception) {
                return CompletableFuture.failedFuture(exception)
            }
        return redisManager.saveMapEntries(key, uuid, json).thenApply { null }
    }

    private fun loadData(uuid: UUID): CompletableFuture<T?> =
        redisManager.loadMapEntries(key, uuid.toString()).thenApply { list ->
            if (list.isNullOrEmpty() || list.first() == null) return@thenApply null
            Common.gson.fromJson(list.first(), clazz)
        }

    private fun applyData(data: T?) {
        if (data == null) {
            debug("No data found in database {} (first visit or not yet saved)", key)
            return
        }
        if (data.server() == ARC.serverName) return
        dataApplier(data)
    }

    private fun applyOnMainThread(data: T?): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        sync {
            try {
                applyData(data)
                result.complete(null)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    fun loadAndApplyData(uuid: UUID): CompletableFuture<Void> =
        loadData(uuid).thenCompose(::applyOnMainThread).whenComplete { _, failure ->
            if (failure != null) {
                error("Failed to load sync data from {} for {}", key, uuid, failure)
            }
        }

    fun saveAndPersistData(context: Context): CompletableFuture<Void> {
        val data =
            try {
                dataProducer(context)
            } catch (exception: Exception) {
                return CompletableFuture.failedFuture<Void>(exception).logSaveFailure()
            }
        val future: CompletableFuture<Void> =
            if (data == null || data.trash()) {
                CompletableFuture.completedFuture(null)
            } else {
                saveDataPersistently(data)
            }
        return future.logSaveFailure()
    }

    private fun CompletableFuture<Void>.logSaveFailure(): CompletableFuture<Void> =
        whenComplete { _, failure ->
            if (failure != null) {
                error("Failed to persist sync data to {}", key, failure)
            }
        }
}
