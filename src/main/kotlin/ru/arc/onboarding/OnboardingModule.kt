package ru.arc.onboarding

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

object OnboardingModule : PluginModule {
    override val name = "Onboarding"
    override val priority = 89

    private val listeners = mutableListOf<Listener>()

    override fun init() {
        OnboardingService.init()
        register(OnboardingPlayerListener())
        registerOptional("Lands") { OnboardingLandsListener() }
        if (OnboardingService.isEnabled()) {
            Bukkit.getOnlinePlayers().forEach(OnboardingService::resume)
        }
        info(
            "Onboarding outcome listeners registered: {}; contextual hints enabled={}",
            listeners.size,
            OnboardingService.isEnabled(),
        )
    }

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        listeners.forEach(HandlerList::unregisterAll)
        listeners.clear()
        OnboardingService.shutdown()
    }

    private fun register(listener: Listener) {
        Bukkit.getPluginManager().registerEvents(listener, ARC.instance)
        listeners += listener
    }

    private fun registerOptional(
        pluginName: String,
        factory: () -> Listener,
    ) {
        val plugin = Bukkit.getPluginManager().getPlugin(pluginName)
        if (plugin?.isEnabled == true) {
            register(factory())
        } else {
            warn("Onboarding integration unavailable: {} is not enabled", pluginName)
        }
    }
}
