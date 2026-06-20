package ru.arc.invest.goods

import org.bukkit.Material
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.inventory.ItemStack

class Inventory(val items: MutableList<ItemStack>) : ConfigurationSerializable {

    @Volatile
    var inUse: Boolean = false

    fun add(stack: ItemStack) {
        items.add(stack)
    }

    fun add(stacks: Collection<ItemStack>) {
        items.addAll(stacks)
    }

    fun remove(itemsToRemove: Collection<ItemStack>) {
        removeItemStacks(items, itemsToRemove)
    }

    fun contains(list: List<ItemStack>): Boolean {
        for (need in list) {
            var needQuantity = need.amount
            val one = need.asOne()
            for (have in items) {
                if (have.asOne() != one) continue
                needQuantity -= have.amount
                if (needQuantity <= 0) break
            }
            if (needQuantity > 0) return false
        }
        return true
    }

    fun trim() {
        val map = count()
        items.clear()
        for ((key, value) in map) {
            var count = value
            while (count > 0) {
                val amount = minOf(64, count)
                items.add(key.asQuantity(amount))
                count -= amount
            }
        }
        sort()
    }

    fun sort() {
        items.sortWith(Comparator.comparing { it.type })
    }

    private fun count(): Map<ItemStack, Int> {
        val map = HashMap<ItemStack, Int>()
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val stack = iterator.next()
            if (stack.type == Material.AIR) {
                iterator.remove()
                continue
            }
            val one = stack.asOne()
            map.merge(one, stack.amount, Int::plus)
        }
        return map
    }

    override fun serialize(): Map<String, Any> =
        mapOf("storage" to items.map { it.serialize() })

    companion object {
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun deserialize(items: List<*>?): Inventory {
            val stacks = ArrayList<ItemStack>()
            if (items == null) return Inventory(stacks)
            for (o in items) {
                if (o !is Map<*, *>) continue
                val map = o as Map<String, Any>
                stacks.add(ItemStack.deserialize(map))
            }
            return Inventory(stacks)
        }

        private fun removeItemStacks(itemList: MutableList<ItemStack>, itemsToRemove: Collection<ItemStack>) {
            val iterator = itemList.iterator()
            for (itemToRemove in itemsToRemove) {
                var remainingQuantity = itemToRemove.amount
                val one = itemToRemove.asOne()
                while (remainingQuantity > 0 && iterator.hasNext()) {
                    val currentItem = iterator.next()
                    if (currentItem.asOne() != one) continue
                    val currentQuantity = currentItem.amount
                    if (currentQuantity <= remainingQuantity) {
                        iterator.remove()
                        remainingQuantity -= currentQuantity
                    } else {
                        currentItem.amount = currentQuantity - remainingQuantity
                        remainingQuantity = 0
                    }
                }
            }
        }
    }
}
