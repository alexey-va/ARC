package ru.arc.ops

import org.bukkit.Bukkit
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.util.Logging.info

/**
 * Local HTTP API for ops tooling and MCP (token-authenticated, bind 127.0.0.1 by default).
 */
object OpsHttpModule : PluginModule {
    override val name = "OpsHttp"
    override val priority = 35

    /** Single instance — new instance per reload orphaned the JDK HttpServer on bind races. */
    private val httpServer = OpsHttpServer()
    private var healthTask: ScheduledTask? = null

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        healthTask?.cancel()
        healthTask = null
        OpsHttpConfig.reload()
        val cfg = OpsHttpConfig.current()
        if (!cfg.enabled) {
            httpServer.stop()
            info("Ops HTTP API disabled")
            return
        }
        healthTask =
            Tasks.scheduler.runTimerAsync(HEALTH_REPORT_TICKS, HEALTH_REPORT_TICKS) {
                info(OpsHttpHandlers.runtimeHealthLine())
            }
        OpsStartupLogTap.install()
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapNpcMarkers.start()
        }
        httpServer.start()
    }

    override fun reload() {
        httpServer.stop()
        // stop(0) can leave the port busy briefly; avoid BindException orphaning the old listener.
        Thread.sleep(50)
        init()
    }

    override fun shutdown() {
        healthTask?.cancel()
        healthTask = null
        httpServer.stop()
        OpsStartupLogTap.uninstall()
    }

    private const val HEALTH_REPORT_TICKS = 1_200L
}
