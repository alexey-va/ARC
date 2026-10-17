package ru.arc.ops

import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.util.Logging.info
import ru.arc.util.Logging.error
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException

/**
 * Local HTTP API for ops tooling and MCP (token-authenticated, bind 127.0.0.1 by default).
 */
object OpsHttpModule : PluginModule {
    override val name = "OpsHttp"
    override val priority = 35

    /** Single instance — new instance per reload orphaned the JDK HttpServer on bind races. */
    private val httpServer = OpsHttpServer()
    private var healthTask: ScheduledTask? = null
    @Volatile private var generation = 0L
    private var ready = CompletableFuture.completedFuture<Void>(null)

    fun readiness(): CompletableFuture<Void> = ready.copy()

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) {
            ready = CompletableFuture.completedFuture(null)
            return
        }
        val plugin = ARC.instance
        val root = plugin.dataPath
        ++generation
        ready.completeExceptionally(CancellationException("Ops HTTP generation replaced"))
        val completion = CompletableFuture<Void>().also { ready = it }
        healthTask?.cancel()
        healthTask = null
        OpsStartupLogTap.uninstall()
        try {
            val enabled = plugin.submitBackground {
                OpsHttpConfig.reload(root)
                httpServer.start()
                OpsHttpConfig.current().enabled
            }.join()
            if (enabled) {
                healthTask = Tasks.scheduler.runTimerAsync(HEALTH_REPORT_TICKS, HEALTH_REPORT_TICKS) {
                    info(OpsHttpHandlers.runtimeHealthLine())
                }
                OpsStartupLogTap.install()
                OpsBlueMapIntegration.startIfEnabled()
            } else info("Ops HTTP API disabled")
            completion.complete(null)
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
            error("Ops HTTP initialization failed", failure)
            plugin.retireInBackground { httpServer.stop() }
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        ++generation
        ready.completeExceptionally(CancellationException("Ops HTTP stopped"))
        healthTask?.cancel()
        healthTask = null
        OpsStartupLogTap.uninstall()
        ARC.instance.retireInBackground { httpServer.stop() }
    }

    private const val HEALTH_REPORT_TICKS = 1_200L
}
