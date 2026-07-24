package ru.arc.ops

import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/**
 * Local HTTP API for ops tooling and MCP (token-authenticated, bind 127.0.0.1 by default).
 */
object OpsHttpModule : PluginModule {
    override val name = "OpsHttp"
    override val priority = 35

    /** Single instance — new instance per reload orphaned the JDK HttpServer on bind races. */
    private val httpServer = OpsHttpServer()

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        OpsHttpConfig.reload()
        val cfg = OpsHttpConfig.current()
        if (!cfg.enabled) {
            httpServer.stop()
            info("Ops HTTP API disabled")
            return
        }
        OpsStartupLogTap.install()
        httpServer.start()
    }

    override fun reload() {
        httpServer.stop()
        // stop(0) can leave the port busy briefly; avoid BindException orphaning the old listener.
        Thread.sleep(50)
        ConfigManager.reloadAll()
        init()
    }

    override fun shutdown() {
        httpServer.stop()
        OpsStartupLogTap.uninstall()
    }
}
