package ru.arc.invest

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import ru.arc.invest.goods.ProductionEntry

class Production(private val productionEntries: List<ProductionEntry>) {

    fun produce(productionIds: Set<String>, level: Int): List<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        for (entry in productionEntries) {
            if (!productionIds.contains(entry.name)) continue
            stacks.addAll(entry.production(level))
        }
        return stacks
    }

    fun reqs(level: Int): Map<String, List<ItemStack>> {
        val reqs = HashMap<String, List<ItemStack>>()
        for (entry in productionEntries) {
            reqs[entry.name] = entry.reqs(level)
        }
        return reqs
    }

    companion object {
        @JvmStatic
        fun deserialize(section: ConfigurationSection?): Production? {
            section ?: return null
            val productionEntryList = ArrayList<ProductionEntry>()
            for (key in section.getKeys(false)) {
                val en = ProductionEntry.deserialize(section.getConfigurationSection(key)!!, key)
                productionEntryList.add(en)
            }
            return Production(productionEntryList)
        }
    }
}
