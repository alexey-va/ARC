package ru.arc.cleanup

import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import java.util.UUID

/**
 * Snapshot of Paper/Spigot's effective item lifetime for loaded worlds.
 *
 * Paper exposes only global server configuration through [Server.getServerConfig].
 * The world item settings are not part of the public Bukkit API, so this class
 * reads Paper's active legacy configuration snapshots once at load/reload time.
 * It deliberately returns null for an unknown or invalid world/material instead
 * of guessing.
 */
internal class NativeItemLifetime private constructor(
    private val worlds: Map<UUID, WorldRules>,
) {
    /** Returns the effective native despawn rate in ticks, or null if unknown. */
    fun ticks(world: World, material: Material): Int? =
        worlds[world.uid]?.ticks(material)

    private data class WorldRules(
        val itemDespawnRate: Int?,
        val altEnabled: Boolean?,
        val altItems: Map<Material, Int>,
        val invalidAltItems: Set<Material>,
    ) {
        fun ticks(material: Material): Int? = when (altEnabled) {
            null -> null
            false -> itemDespawnRate
            true -> if (material in invalidAltItems) null else altItems[material] ?: itemDespawnRate
        }
    }

    companion object {
        private const val PAPER_ALT_ENABLED = "entities.spawning.alt-item-despawn-rate.enabled"
        private const val PAPER_ALT_ITEMS = "entities.spawning.alt-item-despawn-rate.items"
        private const val PAPER_WORLDS = "__________WORLDS__________"
        private const val PAPER_DEFAULTS = "__defaults__"

        fun load(server: Server): NativeItemLifetime {
            // Deprecated Bukkit bridge, but the only public API exposing the
            // active Paper world snapshots on Paper 1.21.11. Paper's
            // createLegacyObject stores defaults and worlds under PAPER_WORLDS
            // (CraftServer/PaperConfigurations, ver/1.21.11).
            val spigot = server.spigot().spigotConfig
            val paper = server.spigot().paperConfig
            val worldSettings = spigot.getConfigurationSection("world-settings")
            val defaultRate = positiveTicks(worldSettings?.getConfigurationSection("default")?.get("item-despawn-rate"))
            val paperWorlds = paper.getConfigurationSection(PAPER_WORLDS)
            val paperDefaults = paperWorlds?.getConfigurationSection(PAPER_DEFAULTS)
            val defaultAltEnabledRaw = paperDefaults?.get(PAPER_ALT_ENABLED)
            val defaultAltEnabled = booleanValue(defaultAltEnabledRaw)
            val defaultAltItems = materialRates(paperDefaults?.getConfigurationSection(PAPER_ALT_ITEMS))

            val snapshots = server.worlds.associate { world ->
                val spigotWorld = worldSettings?.getConfigurationSection(world.name)
                val worldSpigotRate = if (spigotWorld?.contains("item-despawn-rate") == true) {
                    positiveTicks(spigotWorld.get("item-despawn-rate"))
                } else {
                    defaultRate
                }
                val paperWorld = paperWorlds?.getConfigurationSection(world.name)
                val worldAltEnabledRaw = paperWorld?.get(PAPER_ALT_ENABLED)
                val altEnabled = if (worldAltEnabledRaw == null) defaultAltEnabled else booleanValue(worldAltEnabledRaw)
                val worldAltItems = materialRates(paperWorld?.getConfigurationSection(PAPER_ALT_ITEMS))
                val altItems = defaultAltItems.values + worldAltItems.values
                val invalidAltItems = (defaultAltItems.invalid + worldAltItems.invalid) -
                    worldAltItems.values.keys
                world.uid to WorldRules(worldSpigotRate, altEnabled, altItems, invalidAltItems)
            }
            return NativeItemLifetime(snapshots)
        }

        private data class MaterialRates(val values: Map<Material, Int>, val invalid: Set<Material>)

        private fun materialRates(section: org.bukkit.configuration.ConfigurationSection?): MaterialRates {
            if (section == null) return MaterialRates(emptyMap(), emptySet())
            val values = mutableMapOf<Material, Int>()
            val invalid = mutableSetOf<Material>()
            section.getKeys(false).forEach { key ->
                val material = Material.matchMaterial(key, false) ?: return@forEach
                val rate = positiveTicks(section.get(key))
                if (rate == null) invalid += material else values[material] = rate
            }
            return MaterialRates(values.toMap(), invalid.toSet())
        }

        private fun booleanValue(value: Any?): Boolean? = value as? Boolean

        private fun positiveTicks(value: Any?): Int? = when (value) {
            is Byte -> value.toInt().takeIf { it > 0 }
            is Short -> value.toInt().takeIf { it > 0 }
            is Int -> value.takeIf { it > 0 }
            is Long -> value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            is Float -> value.toInt().takeIf { value == it.toFloat() && it > 0 }
            is Double -> value.toInt().takeIf { value == it.toDouble() && it > 0 }
            else -> null
        }
    }
}
