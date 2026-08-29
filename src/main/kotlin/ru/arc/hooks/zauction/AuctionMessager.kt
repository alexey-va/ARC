package ru.arc.hooks.zauction

import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.util.Common
import ru.arc.util.Logging.error

class AuctionMessager(
    val channel: String,
    val channelAll: String,
    val saleChannel: String,
    private val redis: RedisOperations,
) : ChannelListener {

    override fun consume(channel: String, message: String, originServer: String) {}

    fun send(itemDtoList: List<AuctionItemDto>) {
        try {
            // Every scheduled broadcast is the complete shared-auction snapshot.
            // Publishing it as a partial update would leave sold, returned, or
            // expired listings stuck in ProxyARC's in-memory Discord feed.
            redis.publish(channelAll, Common.gson.toJson(itemDtoList))
        } catch (e: Exception) {
            error("Error sending auction items", e)
        }
    }

    fun sendSale(event: AuctionSaleEventDto) {
        try {
            redis.publish(saleChannel, Common.gson.toJson(event))
        } catch (e: Exception) {
            error("Error sending auction sale event", e)
        }
    }
}
