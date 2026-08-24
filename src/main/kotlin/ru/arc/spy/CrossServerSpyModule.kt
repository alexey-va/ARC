package ru.arc.spy

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

object CrossServerSpyModule : PluginModule {
    override val name = "CrossServerSpy"
    override val priority = 67

    private var bridge: CrossServerSpyBridge? = null

    override fun init() {
        stopBridge()
        val settings = CrossServerSpyConfig.load(ARC.instance.dataPath).settings
        if (!settings.enabled) {
            info("Cross-server spy bridge disabled by configuration")
            return
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("CMI")) {
            warn("Cross-server spy bridge requires CMI; bridge not started")
            return
        }
        val redis = ARC.redisManager ?: run {
            warn("Cross-server spy bridge requires Redis; bridge not started")
            return
        }
        val server = CrossServerSpyConfig.normalizeToken(ARC.serverName.orEmpty())
        if (server !in settings.allowedServers) {
            warn("Cross-server spy bridge rejected unconfigured local server identity: {}", server.ifEmpty { "missing" })
            return
        }

        bridge =
            CrossServerSpyBridge(
                plugin = ARC.instance,
                redis = redis,
                localServer = server,
                settings = settings,
                cmi = CmiSpyAccess(),
            ).also(CrossServerSpyBridge::start)
    }

    override fun reload() = init()

    override fun shutdown() = stopBridge()

    private fun stopBridge() {
        bridge?.close()
        bridge = null
    }
}
