package ru.arc.store

import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import ru.arc.util.hasCustomModelDataSafe
import java.util.concurrent.atomic.AtomicBoolean

data class ItemMatcher(
    val material: Material? = null,
    val materialName: String? = null,
    val nbt: Set<String>? = null,
    val hasModelData: Boolean? = null,
    val isSfItem: Boolean? = null,
) {

    fun matches(stack: ItemStack): Boolean {
        if (material != null && stack.type == material) return true

        if (!nbt.isNullOrEmpty()) {
            val allMatch = AtomicBoolean(false)
            NBT.get(stack) { readableItemNBT ->
                allMatch.set(nbt.all { readableItemNBT.hasTag(it) })
            }
            if (allMatch.get()) return true
        }

        if (materialName != null) {
            val mat = stack.type.name.lowercase()
            if (mat.matches(materialName.toRegex())) return true
        }

        if (hasModelData != null && stack.itemMeta != null) {
            if (stack.itemMeta!!.hasCustomModelDataSafe() == hasModelData) return true
        }

        if (isSfItem != null) {
            val meta = stack.itemMeta
            if (meta != null && HookRegistry.sfHook != null &&
                HookRegistry.sfHook!!.isSlimefunItem(stack) == isSfItem
            ) return true
        }

        return false
    }

    companion object {
        @JvmStatic fun of(material: Material) = ItemMatcher(material = material)
        @JvmStatic fun ofNbt(vararg strings: String) = ItemMatcher(nbt = setOf(*strings))
        @JvmStatic fun ofRegex(regex: String) = ItemMatcher(materialName = regex)
        @JvmStatic fun sfItem(isSfItem: Boolean) = ItemMatcher(isSfItem = isSfItem)
        @JvmStatic fun modelData(hasModelData: Boolean) = ItemMatcher(hasModelData = hasModelData)
    }
}
