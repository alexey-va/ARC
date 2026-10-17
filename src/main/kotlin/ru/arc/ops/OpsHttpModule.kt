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
        val epoch = ++generation
        ready.completeExceptionally(CancellationException("Ops HTTP generation replaced"))
        val completion = CompletableFuture<Void>().also { ready = it }
        healthTask?.cancel()
        healthTask = null
        OpsStartupLogTap.uninstall()
        val starting = plugin.submitBackground {
            if (generation != epoch) throw CancellationException("Ops HTTP generation replaced")
            OpsHttpConfig.reload(root)
            // start() owns stop/bind as one serialized worker operation. No sleeps or socket
            // monitors are acquired by module attach, reload or disable on the server thread.
            httpServer.start()
            OpsHttpConfig.current().enabled
        }
        plugin.whenCompleteOnMain(starting) { enabled, failure ->
            if (generation != epoch) return@whenCompleteOnMain
            if (failure != null) {
                completion.completeExceptionally(failure)
                error("Ops HTTP startup failed", failure)
                return@whenCompleteOnMain
            }
            try {
                if (enabled == true) {
                    healthTask = Tasks.scheduler.runTimerAsync(HEALTH_REPORT_TICKS, HEALTH_REPORT_TICKS) {
                        info(OpsHttpHandlers.runtimeHealthLine())
                    }
                    OpsStartupLogTap.install()
                    OpsBlueMapIntegration.startIfEnabled()
                } else info("Ops HTTP API disabled")
                completion.complete(null)
            } catch (attachFailure: Throwable) {
                completion.completeExceptionally(attachFailure)
                error("Ops HTTP integration attach failed", attachFailure)
                plugin.retireInBackground { httpServer.stop() }
            }
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
