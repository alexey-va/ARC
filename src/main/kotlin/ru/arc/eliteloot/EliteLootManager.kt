package ru.arc.eliteloot

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentHashMap

object EliteLootManager {
    private data class State(
        val processor: EliteLootProcessor,
        val parser: EliteLootConfigParser,
        val pools: ConcurrentHashMap<LootType, DecorPool>,
    )

    @Volatile
    private var state: State? = null

    @get:JvmStatic
    val eliteLootProcessor: EliteLootProcessor?
        get() = state?.processor

    @get:JvmStatic
    val map: Map<LootType, DecorPool>
        get() = state?.pools ?: emptyMap()

    @JvmStatic
    @Synchronized
    fun init() {
        if (state != null) return

        val parser = EliteLootConfigParser()
        val pools = ConcurrentHashMap(parser.load())
        val processor = EliteLootProcessor()
        state = State(processor, parser, pools)

        val totalItems = pools.values.sumOf { it.decors.size }
        info("EliteLoot loaded {} loot types, {} total decor items", pools.size, totalItems)
        pools.forEach { (type, pool) ->
            if (pool.decors.isNotEmpty()) info("  {} → {} items", type.name, pool.decors.size)
            else warn("  {} → empty pool!", type.name)
        }
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        state = null
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
    @Synchronized
    fun addDecorItem(
        lootType: LootType,
        material: Material,
        weight: Double,
        modelId: Int,
        color: org.bukkit.Color?,
        iaNamespace: String?,
        iaId: String?,
    ): Boolean {
        val current = state ?: return false
        val decorItem = DecorItem(material, weight, modelId, color, iaNamespace, iaId)
        val pool = current.pools.computeIfAbsent(lootType) { DecorPool() }
        if (pool.contains(decorItem)) return false
        current.parser.addDecor(lootType, decorItem)
        pool.add(decorItem, weight)
        return true
    }
}
