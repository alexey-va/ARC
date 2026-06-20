package ru.arc.hooks.zauction

import ru.arc.network.ChannelListener
import ru.arc.network.RedisManager
import ru.arc.util.Common
import ru.arc.util.Logging.error

class AuctionMessager(
    val channel: String,
    val channelAll: String,
    private val redisManager: RedisManager,
) : ChannelListener {

    override fun consume(channel: String, message: String, originServer: String) {}

    fun send(itemDtoList: List<AuctionItemDto>) {
        try {
            redisManager.publish(channel, Common.gson.toJson(itemDtoList))
        } catch (e: Exception) {
            error("Error sending auction items", e)
        }
    }
}
