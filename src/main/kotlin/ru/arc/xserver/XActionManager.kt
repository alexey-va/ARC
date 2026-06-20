package ru.arc.xserver

import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.withContext

object XActionManager {

    private var messager: XActionMessager? = null

    @JvmStatic
    fun init() {
        messager = XActionMessager()
        val redis = ARC.redisManager ?: run {
            error("Redis manager is not initialized. XActionManager will not work.")
            return
        }
        redis.registerChannelUnique(XActionMessager.CHANNEL, messager!!)
        info("XActionManager registered channel: {}", XActionMessager.CHANNEL)
    }

    @JvmStatic
    fun run(action: XAction) {
        withContext("xaction", null, "run") {
            info("[XAction] Running action on this server: {}", action)
            action.run()
        }
    }

    @JvmStatic
    fun publish(action: XAction) {
        val m = messager ?: run {
            error("[XAction] Cannot publish — messager is null (Redis not initialized?)")
            return
        }
        withContext("xaction", null, "publish") {
            info("[XAction] Publishing action: {}", action)
            m.send(action)
        }
    }

    @JvmStatic
    fun movePlayerToServer(player: Player, server: String) {
        ARC.pluginMessenger?.sendPlayerToServer(player, server)
    }
}
