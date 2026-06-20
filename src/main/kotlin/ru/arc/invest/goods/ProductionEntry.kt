package ru.arc.invest.goods

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import ru.arc.invest.items.GenericItem
import ru.arc.util.ItemUtils
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom

class ProductionEntry(
    private val minLevel: Int,
    private val maxLevel: Int,
    private val produce: List<Good>,
    private val consumption: List<Good>,
    val name: String,
) {
    data class Good(val genericItem: GenericItem, val min: Int, val max: Int, val chance: Double, val durability: Double)
    data class UnsatisfiedDemand(val name: String, val map: MutableMap<Good, Int>)

    fun production(level: Int): List<ItemStack> {
        if (level < minLevel || level > maxLevel) return emptyList()
        val stacks = ArrayList<ItemStack>()
        for (good in produce) {
            if (ThreadLocalRandom.current().nextDouble() > good.chance) continue
            val amount = ThreadLocalRandom.current().nextInt(good.min, good.max + 1)
            stacks.addAll(ItemUtils.split(good.genericItem.stack(), amount))
        }
        return stacks
    }

    fun reqs(level: Int): List<ItemStack> {
        if (level < minLevel || level > maxLevel) return emptyList()
        val stacks = ArrayList<ItemStack>()
        for (good in consumption) {
            if (ThreadLocalRandom.current().nextDouble() > good.chance) continue
            val amount = ThreadLocalRandom.current().nextInt(good.min, good.max + 1)
            stacks.addAll(ItemUtils.split(good.genericItem.stack(), amount))
        }
        return stacks
    }

    fun possible(stacks: Collection<ItemStack>): Optional<UnsatisfiedDemand> {
        val cloneStacks = stacks.map { it.asOne() }
        val demand = UnsatisfiedDemand(name, HashMap())
        for (good in consumption) {
            if (good.chance < 1.0) continue
            var need = good.min
            for (stack in cloneStacks) {
                if (good.genericItem.fits(stack)) {
                    need -= stack.amount
                    if (need <= 0) break
                }
            }
            if (need > 0) demand.map[good] = need
        }
        return if (demand.map.isNotEmpty()) Optional.of(demand) else Optional.empty()
    }

    companion object {
        @JvmStatic
        fun deserialize(section: ConfigurationSection, id: String): ProductionEntry {
            val minLevel = section.getInt("min-level", 1)
            val maxLevel = section.getInt("max-level", Int.MAX_VALUE)
            val produce = getGoodsList(section.getConfigurationSection("produce"))
            println(produce)
            val consumption = getGoodsList(section.getConfigurationSection("consumption"))
            println(consumption)
            return ProductionEntry(minLevel, maxLevel, produce, consumption, id)
        }

        private fun getGoodsList(goods: ConfigurationSection?): List<Good> {
            if (goods == null) {
                println("No goods specified for ")
                return emptyList()
            }
            val goodList = ArrayList<Good>()
            for (key in goods.getKeys(false)) {
                val section = goods.getConfigurationSection(key) ?: continue
                val genericItem = GenericItem.fromString(section.getString("item") ?: "")
                if (genericItem.isEmpty) {
                    println("Could not parse item: ${section.getString("item")}")
                    continue
                }
                val durability = section.getDouble("durability", 0.0)
                var min = 0
                var max = 0
                when (val q = section.get("count", 1)) {
                    is Int -> { min = q; max = q }
                    is String -> { min = q.split("-")[0].toInt(); max = q.split("-")[1].toInt() }
                }
                val chance = section.getDouble("chance", 1.0)
                goodList.add(Good(genericItem.get(), min, max, chance, durability))
            }
            return goodList
        }
    }
}
