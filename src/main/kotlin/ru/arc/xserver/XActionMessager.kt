package ru.arc.xserver

import ru.arc.ARC
import ru.arc.network.ChannelListener
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.withContext
import java.util.concurrent.CompletableFuture

class XActionMessager : ChannelListener {

    companion object {
        const val CHANNEL = "arc.xactions"
    }

    override fun consume(channel: String, message: String, originServer: String) {
        withContext("xaction", null, "receive") { consumeInternal(channel, message, originServer) }
    }

    private fun consumeInternal(channel: String, message: String, originServer: String) {
        info("[XAction] Received message on channel '{}' from server '{}': {}", channel, originServer, message)
        val action = try {
            Common.gson.fromJson(message, XAction::class.java)
        } catch (e: Exception) {
            error("[XAction] Failed to deserialize action from server '{}': {}", originServer, message, e)
            return
        }
        if (action == null) {
            error("[XAction] Deserialized null action from server '{}': {}", originServer, message)
            return
        }
        info("[XAction] Deserialized action type={} from server '{}'", action.javaClass.simpleName, originServer)
        XActionManager.run(action)
    }

    fun send(action: XAction) {
        CompletableFuture.supplyAsync {
            Common.gson.toJson(action).also { json ->
                info("[XAction] Serialized for publish: {}", json)
            }
        }.thenAccept { str ->
            val redis = ARC.redisManager ?: run {
                error("[XAction] Cannot publish — redisManager is null")
                return@thenAccept
            }
            redis.publish(CHANNEL, str)
            info("[XAction] Published to channel '{}'", CHANNEL)
        }.exceptionally { e ->
            error("[XAction] Exception during publish", e)
            null
        }
    }
}
