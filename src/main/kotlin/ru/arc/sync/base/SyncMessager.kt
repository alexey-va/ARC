package ru.arc.sync.base

import ru.arc.ARC
import ru.arc.network.ChannelListener
import ru.arc.network.RedisManager
import ru.arc.util.Logging.debug

class SyncMessager<T : SyncData>(
    val redisManager: RedisManager,
    val channel: String,
    val repo: SyncRepo<T>,
) : ChannelListener {

    override fun consume(channel: String, message: String, originServer: String) {
        if (originServer == ARC.serverName) return
        val data: T = repo.gson.fromJson(message, repo.clazz)
        debug("Received data from channel: {} with data: {}", channel, data)
        repo.dataApplier(data)
    }

    fun send(data: T) {
        debug("Sending data to channel: {}", channel)
        redisManager.publish(channel, repo.gson.toJson(data))
    }
}
