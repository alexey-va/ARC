package ru.arc.parkour

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

object ArcParkourModule : PluginModule {
    override val name = "ParkourPresentation"
    override val priority = 85

    private val listeners = mutableListOf<Listener>()
    private var tasks: LifecycleTaskScope? = null

    override fun init() {
        val settings =
            runCatching { ArcParkourConfig.load(ARC.instance.dataPath).snapshot() }
                .onFailure { warn("ARC Parkour presentation configuration rejected: {}", it.message ?: it.javaClass.simpleName) }
                .getOrNull() ?: return
        if (!settings.enabled) {
            info("ARC Parkour presentation module disabled by configuration")
            return
        }
        val plugin = Bukkit.getPluginManager().getPlugin("Parkour")
        if (plugin == null || !plugin.isEnabled) {
            warn("ARC Parkour presentation unavailable because Parkour is not enabled")
            return
        }

        val activeTasks = LifecycleTaskScope().also { it.restart() }
        val integrationListeners =
            runCatching { NativeParkourIntegration.listeners(plugin, settings, activeTasks) }
                .onFailure { warn("ARC Parkour presentation rejected the installed Parkour API: {}", it.message ?: it.javaClass.simpleName) }
                .getOrNull()
        if (integrationListeners == null) {
            activeTasks.close()
            return
        }
        tasks = activeTasks
        runCatching { integrationListeners.forEach(::register) }
            .onFailure {
                warn("ARC Parkour presentation listener registration failed: {}", it.message ?: it.javaClass.simpleName)
                shutdown()
                return
            }
        info("ARC Parkour presentation initialized: {} categories", settings.categories.size)
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
        tasks?.close()
        tasks = null
    }

    private fun register(listener: Listener) {
        Bukkit.getPluginManager().registerEvents(listener, ARC.instance)
        listeners += listener
    }
}
