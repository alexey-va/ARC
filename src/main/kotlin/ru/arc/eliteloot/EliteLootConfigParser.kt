package ru.arc.eliteloot

import org.bukkit.Color
import org.bukkit.Material
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.warn

class EliteLootConfigParser {

    private val config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "elite-loot.yml")

    fun load(): Map<LootType, DecorPool> {
        val res = mutableMapOf<LootType, DecorPool>()
        debug("Loading elite loot config for {} loot types", LootType.entries.size)
        for (lootType in LootType.entries) {
            val pool = parseDecorPool(lootType)
            res[lootType] = pool
            debug("Loaded loot type {} with {} items", lootType, pool.decors.size)
        }
        applyCrossbowFallback(res)
        return res
    }

    private fun applyCrossbowFallback(pools: MutableMap<LootType, DecorPool>) {
        val crossbowPool = pools[LootType.CROSSBOW]
        if (crossbowPool == null || crossbowPool.decors.isNotEmpty()) return
        val bowPool = pools[LootType.BOW]
        if (bowPool == null || bowPool.decors.isEmpty()) return

        val fallback = DecorPool()
        for (bowItem in bowPool.decors.values) {
            fallback.add(
                DecorItem(Material.CROSSBOW, bowItem.weight, bowItem.modelId, bowItem.color, bowItem.iaNamespace, bowItem.iaId),
                bowItem.weight,
            )
        }
        pools[LootType.CROSSBOW] = fallback
        debug("Crossbow pool empty — reusing {} bow decor entries as crossbow", fallback.decors.size)
    }

    fun addDecor(lootType: LootType, decorItem: DecorItem) {
        val serialized = mutableMapOf<String, Any>(
            "material" to decorItem.material.name,
            "model-id" to decorItem.modelId,
            "weight" to decorItem.weight,
            "ia-namespace" to (decorItem.iaNamespace ?: ""),
            "ia-id" to (decorItem.iaId ?: ""),
        )
        decorItem.color?.let {
            serialized["red"] = it.red
            serialized["green"] = it.green
            serialized["blue"] = it.blue
        }
        config.addToList("elite-loot.${lootType.name.lowercase()}", serialized)
    }

    private fun parseDecorPool(lootType: LootType): DecorPool {
        val pool = DecorPool()
        val decors: MutableList<Map<String, Any>> = config.list("elite-loot.${lootType.name.lowercase()}")
        if (decors.isEmpty()) return pool

        for (decor in decors) {
            val materialStr = decor["material"]?.toString()?.uppercase() ?: continue
            val material = Material.matchMaterial(materialStr) ?: run {
                warn("Invalid material: {}. Skipping...", decor["material"])
                continue
            }
            val modelId = (decor["model-id"] as? Number)?.toInt() ?: 0
            val weight = (decor["weight"] as? Number)?.toDouble() ?: 1.0
            val red = (decor["red"] as? Number)?.toInt()
            val green = (decor["green"] as? Number)?.toInt()
            val blue = (decor["blue"] as? Number)?.toInt()
            val iaNamespace = decor["ia-namespace"] as? String
            val iaId = decor["ia-id"] as? String
            val color = if (red != null && green != null && blue != null) Color.fromRGB(red, green, blue) else null

            pool.add(DecorItem(material, weight, modelId, color, iaNamespace, iaId), weight)
        }
        return pool
    }
}
