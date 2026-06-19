package ru.arc.xserver.announcements

import ru.arc.ARC
import ru.arc.network.ChannelListener
import ru.arc.util.Common
import ru.arc.xserver.XMessage
import java.util.concurrent.CompletableFuture

class AnnouncementMessager(val channel: String) : ChannelListener {

    override fun consume(channel: String, message: String, server: String) {
        val data = Common.gson.fromJson(message, XMessage::class.java)
        AnnounceManager.queue(data)
    }

    fun send(data: XMessage) {
        CompletableFuture.supplyAsync { Common.gson.toJson(data) }
            .thenAccept { json -> ARC.redisManager!!.publish(channel, json) }
    }
}
