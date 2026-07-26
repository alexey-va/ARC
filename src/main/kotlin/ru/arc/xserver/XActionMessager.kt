package ru.arc.xserver

import ru.arc.redis.RedisOperations
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.withContext
import ru.arc.redis.xaction.TypedRedisBus
import java.util.concurrent.atomic.AtomicBoolean

class XActionMessager(
    redis: RedisOperations,
    private val scheduler: TaskScheduler = Tasks.scheduler,
) : AutoCloseable {

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
    private val registered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun register() {
        check(!closed.get()) { "Cannot register a closed XActionMessager" }
        if (registered.compareAndSet(false, true)) {
            bus.register()
        }
    }

    fun send(action: XAction) {
        if (closed.get()) {
            error("[XAction] Cannot publish through a closed messager")
            return
        }
        scheduler.runAsync {
            if (closed.get()) return@runAsync
            try {
                bus.publish(action)
                info("[XAction] Published to channel '{}'", CHANNEL)
            } catch (e: Exception) {
                error("[XAction] Exception during publish", e)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (registered.compareAndSet(true, false)) {
            bus.unregister()
        }
    }
}
