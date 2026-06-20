package ru.arc.sync.base

import com.google.gson.Gson
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.network.RedisManager
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SyncRepo<T : SyncData>(
    val clazz: Class<T>,
    val key: String,
    val redisManager: RedisManager,
    val dataApplier: (T) -> Unit,
    val dataProducer: (Context) -> T?,
) {
    val gson: Gson = Gson()

    companion object {
        @JvmStatic
        fun <T : SyncData> builder(clazz: Class<T>): SyncRepoBuilder<T> = SyncRepoBuilder(clazz)
    }

    private fun saveDataPersistently(data: T): CompletableFuture<Void> {
        val uuid = data.uuid()?.toString() ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync { gson.toJson(data) }
            .thenAccept { json -> redisManager.saveMapEntries(key, uuid, json) }
    }

    private fun loadData(uuid: UUID): CompletableFuture<T?> =
        redisManager.loadMapEntries(key, uuid.toString()).thenApply { list ->
            if (list.isNullOrEmpty() || list.first() == null) return@thenApply null
            gson.fromJson(list.first(), clazz)
        }

    private fun applyData(data: T?) {
        if (data == null) {
            debug("No data found in database {} (first visit or not yet saved)", key)
            return
        }
        if (data.server() == ARC.serverName) return
        dataApplier(data)
    }

    private fun produceData(context: Context, async: Boolean): CompletableFuture<T?> =
        if (async) CompletableFuture.supplyAsync { dataProducer(context) }
        else CompletableFuture.completedFuture(dataProducer(context))

    fun loadAndApplyData(uuid: UUID, async: Boolean): CompletableFuture<Void> =
        loadData(uuid).thenAccept { data ->
            try {
                if (async) applyData(data)
                else Bukkit.getScheduler().runTask(ARC.instance, Runnable { applyData(data) })
            } catch (e: Exception) {
                error("Error loading and applying data", e)
            }
        }

    fun saveAndPersistData(context: Context, async: Boolean): CompletableFuture<Void?> =
        produceData(context, async).thenAccept { data ->
            if (data == null || data.trash()) return@thenAccept
            saveDataPersistently(data)
        }
}
