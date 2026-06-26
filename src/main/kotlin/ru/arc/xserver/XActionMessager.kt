package ru.arc.xserver

import ru.arc.redis.RedisOperations
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.withContext
import ru.arc.redis.xaction.TypedRedisBus
import java.util.concurrent.CompletableFuture

class XActionMessager(redis: RedisOperations) {

    companion object {
        const val CHANNEL = "arc.xactions"
    }

    private val bus = TypedRedisBus(
        redis = redis,
        channel = CHANNEL,
        gson = Common.gson,
        messageType = XAction::class.java,
        onMessage = { action, originServer ->
            withContext("xaction", null, "receive") {
                info("[XAction] Deserialized action type={} from server '{}'", action.javaClass.simpleName, originServer)
                XActionManager.run(action)
            }
        },
    )

    fun register() = bus.register()

    fun send(action: XAction) {
        CompletableFuture.runAsync {
            try {
                bus.publish(action)
                info("[XAction] Published to channel '{}'", CHANNEL)
            } catch (e: Exception) {
                error("[XAction] Exception during publish", e)
            }
        }
    }
}
