package ru.arc.stock

import com.google.gson.reflect.TypeToken
import ru.arc.ARC
import ru.arc.network.ChannelListener
import ru.arc.network.RedisManager
import ru.arc.util.Common
import ru.arc.util.Logging.error

class HistoryMessager(
    val channel: String,
    private val redisManager: RedisManager,
) : ChannelListener {

    override fun consume(channel: String, message: String, originServer: String) {
        if (ARC.serverName.equals(originServer, ignoreCase = true)) return
        try {
            val typeToken = object : TypeToken<Map<String, HistoryManager.HighLow>>() {}
            val highLowMap: Map<String, HistoryManager.HighLow> = Common.gson.fromJson(message, typeToken.type)
            HistoryManager.setHighLows(highLowMap)
        } catch (e: Exception) {
            error("Error consuming highlows", e)
        }
    }

    fun send(highLowMap: Map<String, HistoryManager.HighLow>) {
        try {
            redisManager.publish(channel, Common.gson.toJson(highLowMap))
        } catch (e: Exception) {
            error("Error sending highlows", e)
        }
    }
}
