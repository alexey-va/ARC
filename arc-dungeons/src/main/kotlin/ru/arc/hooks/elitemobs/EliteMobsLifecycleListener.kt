package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.MetadataHandler
import com.magmaguy.elitemobs.PluginState
import com.magmaguy.elitemobs.api.EliteMobsInitializedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** The native ready event is repeated after /em reload; plugin load order alone is insufficient. */
internal class EliteMobsLifecycleListener(private val onReady: () -> Unit) : Listener {
    @EventHandler
    fun onInitialized(event: EliteMobsInitializedEvent) = onReady()

    companion object {
        fun isReady(): Boolean = MetadataHandler.pluginState == PluginState.INITIALIZED
    }
}
