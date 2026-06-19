package ru.arc.ops

import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/**
 * Local HTTP API for ops tooling and MCP (token-authenticated, bind 127.0.0.1 by default).
 */
object OpsHttpModule : PluginModule {
    override val name = "OpsHttp"
    override val priority = 35

    private var server: OpsHttpServer? = null

    override fun init() {
        OpsHttpConfig.reload()
        val cfg = OpsHttpConfig.current()
        if (!cfg.enabled) {
            info("Ops HTTP API disabled")
            return
        }
        OpsStartupLogTap.install()
        server = OpsHttpServer().also { it.start() }
    }

    override fun reload() {
        shutdown()
        ConfigManager.reloadAll()
        init()
    }

    override fun shutdown() {
        server?.stop()
        server = null
        OpsStartupLogTap.uninstall()
    }
}
