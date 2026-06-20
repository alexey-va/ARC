package ru.arc.eliteloot

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentHashMap

object EliteLootManager {

    @JvmStatic var eliteLootProcessor: EliteLootProcessor? = null
        private set
    @JvmStatic var eliteLootConfigParser: EliteLootConfigParser? = null
        private set
    @JvmStatic var map: MutableMap<LootType, DecorPool> = ConcurrentHashMap()
        private set

    @JvmStatic
    fun init() {
        eliteLootConfigParser = EliteLootConfigParser()
        map = ConcurrentHashMap(eliteLootConfigParser!!.load())
        eliteLootProcessor = EliteLootProcessor()
        val totalItems = map.values.sumOf { it.decors.size }
        info("EliteLoot loaded {} loot types, {} total decor items", map.size, totalItems)
        map.forEach { (type, pool) ->
            if (pool.decors.isNotEmpty()) info("  {} → {} items", type.name, pool.decors.size)
            else warn("  {} → empty pool!", type.name)
        }
    }

    @JvmStatic
    fun toLootType(stack: ItemStack?): LootType? = stack?.let { toLootType(it.type) }

    @JvmStatic
    fun toLootType(material: Material): LootType? = when (material) {
        Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.GOLDEN_AXE,
        Material.DIAMOND_AXE, Material.NETHERITE_AXE -> LootType.AXE

        Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.GOLDEN_SWORD,
        Material.DIAMOND_SWORD, Material.NETHERITE_SWORD -> LootType.SWORD

        Material.BOW -> LootType.BOW
        Material.CROSSBOW -> LootType.CROSSBOW

        Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.GOLDEN_HELMET,
        Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET -> LootType.HELMET

        Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.GOLDEN_CHESTPLATE,
        Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE -> LootType.CHESTPLATE

        Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.GOLDEN_LEGGINGS,
        Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS -> LootType.LEGGINGS

        Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.GOLDEN_BOOTS,
        Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS -> LootType.BOOTS

        else -> null
    }

    @JvmStatic
    fun addDecorItem(
        lootType: LootType,
        material: Material,
        weight: Double,
        modelId: Int,
        color: org.bukkit.Color?,
        iaNamespace: String?,
        iaId: String?,
    ): Boolean {
        val decorItem = DecorItem(material, weight, modelId, color, iaNamespace, iaId)
        val pool = map.computeIfAbsent(lootType) { DecorPool() }
        if (pool.contains(decorItem)) return false
        eliteLootConfigParser?.addDecor(lootType, decorItem)
        pool.add(decorItem, weight)
        return true
    }
}
