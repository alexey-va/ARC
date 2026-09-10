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
    internal var claimGuide: ClaimBlockGuide? = null
        private set

    fun claimGuideView(player: org.bukkit.entity.Player): ClaimGuideView =
        claimGuide?.view(player) ?: ClaimGuideView()

    fun adjustClaimGuideView(player: org.bukkit.entity.Player, gridSteps: Int = 0, labelSteps: Int = 0, reset: Boolean = false) {
        claimGuide?.adjustView(player, gridSteps, labelSteps, reset)
    }

    override fun init() {
        OnboardingService.init()
        register(OnboardingPlayerListener())
        registerOptional("Lands") { OnboardingLandsListener() }
        if (Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            OnboardingService.guideConfig()?.takeIf { it.claimGuideEnabled }?.let { config ->
                claimGuide = ClaimBlockGuide(config).also { register(it); it.start() }
                register(ClaimBoundaryListener(config))
            }
        }
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
        claimGuide?.close()
        claimGuide = null
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
