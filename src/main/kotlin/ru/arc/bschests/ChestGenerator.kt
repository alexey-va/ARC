package ru.arc.bschests

import ru.arc.config.Config
import ru.arc.network.repos.ItemList
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.Treasures

class ChestGenerator(private val config: Config) {

    fun generate(poolName: String, amount: Int, size: Int): ItemList {
        val pool = Treasures.getOrCreate(poolName)
        val items = ItemList()
        repeat(amount) {
            val treasure = pool.random()
            if (treasure is Treasure.Item) {
                val stack = treasure.stack
                val itemAmount = if (treasure.min == treasure.max) treasure.min
                    else treasure.min + (Math.random() * (treasure.max - treasure.min + 1)).toInt()
                items.add(stack.asQuantity(itemAmount))
            }
        }
        while (items.size < size) items.add(null)
        items.shuffle()
        return items
    }
}
