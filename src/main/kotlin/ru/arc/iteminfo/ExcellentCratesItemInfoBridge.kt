package ru.arc.iteminfo

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/** Optional, fail-open bridge to ArcExcellentCrates' semantic position registry. */
internal object ExcellentCratesItemInfoBridge {
    private const val PLUGIN = "ArcExcellentCrates"
    private const val METHOD = "isCrateLocation"
    private var binding: Binding? = null

    fun contains(location: Location): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin(PLUGIN)
        if (plugin == null || !plugin.isEnabled) {
            binding = null
            return false
        }
        val active = binding?.takeIf { it.plugin === plugin } ?: bind(plugin)?.also { binding = it }
            ?: return false
        return runCatching { active.method.invoke(plugin, location) == true }.getOrDefault(false)
    }

    private fun bind(plugin: Plugin): Binding? = runCatching {
        Binding(plugin, plugin.javaClass.getMethod(METHOD, Location::class.java))
    }.getOrNull()

    private data class Binding(val plugin: Plugin, val method: Method)
}
