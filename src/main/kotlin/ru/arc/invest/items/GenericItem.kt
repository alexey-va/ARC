package ru.arc.invest.items

import org.bukkit.inventory.ItemStack
import java.util.Optional

abstract class GenericItem {
    abstract fun stack(): ItemStack
    abstract fun fits(stack: ItemStack): Boolean
    open fun isTool(): Boolean = false

    companion object {
        @JvmStatic
        fun fromString(s: String): Optional<GenericItem> {
            val namedItem = NamedItem.find(s)
            println("Named item from $s = $namedItem")
            if (namedItem != null) return Optional.of(namedItem)
            val simpleItem = SimpleItem.fromMaterial(s) ?: return Optional.empty()
            return Optional.of(simpleItem)
        }
    }
}
