package ru.arc.slimefunmenu

import net.kyori.adventure.text.Component
import net.william278.huskhomes.event.HomeCreateEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.Plugin
import ru.arc.ARC
import ru.arc.config.ArcRuntimeProfile
import java.util.Locale

/** Keeps the Slimefun SkyBlock return point native to SuperiorSkyblock. */
internal class SlimefunHomePolicy(
    private val plugin: Plugin = ARC.instance,
    private val runtimeProfile: () -> ArcRuntimeProfile = { ARC.instance.runtimeProfile },
    huskHomesEnabled: () -> Boolean = { Bukkit.getPluginManager().isPluginEnabled(HUSK_HOMES_PLUGIN) },
) : Listener, AutoCloseable {
    private var huskHomesGuard: HuskHomesHomeCreateGuard? = null

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        if (huskHomesEnabled()) {
            HuskHomesHomeCreateGuard(runtimeProfile).also { guard ->
                Bukkit.getPluginManager().registerEvents(guard, plugin)
                huskHomesGuard = guard
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (runtimeProfile() != ArcRuntimeProfile.SLIMEFUN || !isHomeCreationCommand(event.message)) return

        event.isCancelled = true
        event.player.sendMessage(BLOCKED_MESSAGE)
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        huskHomesGuard?.close()
        huskHomesGuard = null
    }

    companion object {
        private const val HUSK_HOMES_PLUGIN = "HuskHomes"
        internal const val BLOCKED_TEXT = "На SkyBlock дома не создаются. Используйте /is home."
        private val BLOCKED_MESSAGE = Component.text(BLOCKED_TEXT)

        internal fun isHomeCreationCommand(commandLine: String): Boolean {
            val args = commandLine.trim().removePrefix("/").split(WHITESPACE).filter(String::isNotEmpty)
            val label = args.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
            val subCommand = args.getOrNull(1)?.lowercase(Locale.ROOT)
            return when (label) {
                "sethome", "cmi:sethome", "huskhomes:sethome" -> true
                "cmi", "cmi:cmi", "huskhomes", "huskhomes:huskhomes", "hh", "huskhomes:hh" ->
                    subCommand == "sethome"
                else -> false
            }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

private class HuskHomesHomeCreateGuard(
    private val runtimeProfile: () -> ArcRuntimeProfile,
) : Listener, AutoCloseable {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHomeCreate(event: HomeCreateEvent) {
        if (runtimeProfile() != ArcRuntimeProfile.SLIMEFUN) return

        event.isCancelled = true
        event.creator.audience.sendMessage(Component.text(SlimefunHomePolicy.BLOCKED_TEXT))
    }

    override fun close() {
        HandlerList.unregisterAll(this)
    }
}
