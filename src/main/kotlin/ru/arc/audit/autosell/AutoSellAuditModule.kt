package ru.arc.audit.autosell

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.util.Logging.info
import java.util.UUID

/** Main-thread sampler for aggregate, identity-free AutoSell runtime diagnostics. */
object AutoSellAuditModule : PluginModule {
    override val name = "AutoSellAudit"
    override val priority = 54

    private var task: ScheduledTask? = null
    private val service = AutoSellAuditService()

    override fun init() {
        service.reset()
        if (System.getProperty("arc.test.unit") != null) return
        sample()
        task = Tasks.scheduler.runTimer(SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS, Runnable(::sample))
        info("AutoSell audit sampler started on {}", ARC.serverName ?: "unknown")
    }

    override fun shutdown() {
        task?.cancel()
        task = null
    }

    fun summary(): Map<String, Any?> =
        LinkedHashMap(service.summary()).also { it["localServer"] = ARC.serverName ?: "unknown" }

    fun recordPreTransaction(itemQuantity: Int) {
        service.recordCapture(itemQuantity)
    }

    private fun sample() {
        val plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) as? JavaPlugin
        if (plugin == null || !plugin.isEnabled) {
            service.unavailable("plugin_missing")
            return
        }
        runCatching {
            val pluginVersion = plugin.pluginMeta.version
            AutoSellReflectionReader.read(
                plugin = plugin,
                pluginVersion = pluginVersion,
                ownerMustBeOnline = plugin.config.getBoolean("online-chest-owner", true),
                ownerOnline = ::ownerOnline,
            )
        }.onSuccess(service::accept)
            .onFailure { failure ->
                val status = if (failure is NoSuchMethodException) "unsupported" else "error"
                service.unavailable(status, failure, plugin.pluginMeta.version)
            }
    }

    private fun ownerOnline(owner: UUID): Boolean = Bukkit.getPlayer(owner)?.isOnline == true

    private const val PLUGIN_NAME = "AutoSellChests"
    private const val SAMPLE_PERIOD_TICKS = 20L * 60L
}
