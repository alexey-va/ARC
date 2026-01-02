package ru.arc.repository.redis

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.arc.network.ChannelListener
import ru.arc.network.RedisOperations
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import ru.arc.repository.SyncService
import ru.arc.util.Logging
import java.lang.reflect.Type

/**
 * Redis pub/sub implementation of SyncService.
 */
class RedisSyncService<T : Entity>(
    private val redis: RedisOperations,
    private val channel: String,
    private val entityType: Type,
    private val gson: Gson = Gson()
) : SyncService<T> {

    private var updateHandler: (suspend (T) -> Unit)? = null
    private var deleteHandler: (suspend (String) -> Unit)? = null

    private val listener = ChannelListener { ch, message, _ ->
        if (ch == channel) {
            processMessage(message)
        }
    }

    override suspend fun broadcastUpdate(entity: T): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val message = SyncMessage(
                type = MessageType.UPDATE,
                id = entity.id(),
                data = gson.toJson(entity)
            )
            redis.publish(channel, gson.toJson(message))
        }
    }

    override suspend fun broadcastDelete(id: String): RepoResult<Unit> = withContext(Dispatchers.IO) {
        RepoResult.runCatching {
            val message = SyncMessage(
                type = MessageType.DELETE,
                id = id,
                data = null
            )
            redis.publish(channel, gson.toJson(message))
        }
    }

    override fun onUpdate(handler: suspend (T) -> Unit) {
        updateHandler = handler
    }

    override fun onDelete(handler: suspend (String) -> Unit) {
        deleteHandler = handler
    }

    override fun start() {
        redis.registerChannelUnique(channel, listener)
        Logging.debug("Subscribed to sync channel: $channel")
    }

    override fun stop() {
        redis.unregisterChannel(channel, listener)
        Logging.debug("Unsubscribed from sync channel: $channel")
    }

    @Suppress("UNCHECKED_CAST")
    private fun processMessage(json: String) {
        try {
            val message = gson.fromJson(json, SyncMessage::class.java)

            when (message.type) {
                MessageType.UPDATE -> {
                    val entity = gson.fromJson(message.data, entityType) as T
                    updateHandler?.let { handler ->
                        runBlocking {
                            handler(entity)
                        }
                    }
                }

                MessageType.DELETE -> {
                    deleteHandler?.let { handler ->
                        runBlocking {
                            handler(message.id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logging.warn("Failed to process sync message: ${e.message}")
        }
    }

    private data class SyncMessage(
        val type: MessageType,
        val id: String,
        val data: String?
    )

    private enum class MessageType {
        UPDATE, DELETE
    }
}

