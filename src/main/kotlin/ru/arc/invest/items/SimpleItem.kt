package ru.arc.invest.items

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class SimpleItem(private val material: Material) : GenericItem() {

    override fun stack(): ItemStack = ItemStack(material)

    override fun fits(stack: ItemStack): Boolean = material == stack.type

    override fun toString(): String = "SimpleItem{material=$material}"

    companion object {
        @JvmStatic
        fun deserialize(type: String): SimpleItem = SimpleItem(Material.matchMaterial(type.uppercase())!!)

        @JvmStatic
        fun fromMaterial(s: String): SimpleItem? {
            val material = Material.matchMaterial(s.uppercase()) ?: return null
            return SimpleItem(material)
        }
    }
}
