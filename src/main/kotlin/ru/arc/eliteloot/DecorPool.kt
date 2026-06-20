package ru.arc.eliteloot

import ru.arc.util.Logging.debug
import java.util.TreeMap
import java.util.concurrent.ThreadLocalRandom

class DecorPool {

    val decors: TreeMap<Double, DecorItem> = TreeMap()

    fun add(decorItem: DecorItem, weight: Double) {
        debug("Adding decor item: {} with weight: {}", decorItem.material, weight)
        if (weight == 0.0) return
        val lastKey = if (decors.isEmpty()) 0.0 else decors.lastKey()
        decors[lastKey + weight] = decorItem
    }

    fun randomItem(): DecorItem? {
        if (decors.isEmpty()) return null
        val random = ThreadLocalRandom.current().nextDouble(0.0, decors.lastKey())
        return decors.ceilingEntry(random)?.value
    }

    fun contains(decorItem: DecorItem): Boolean = decors.containsValue(decorItem)

    fun remove(decorItem: DecorItem) {
        decors.values.removeIf { it == decorItem }
    }

    override fun toString(): String = "DecorPool(decors=$decors)"
}
