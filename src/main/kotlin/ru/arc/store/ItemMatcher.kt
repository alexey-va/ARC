package ru.arc.store

import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.HookRegistry
import ru.arc.util.hasCustomModelDataSafe

data class ItemMatcher(
    val material: Material? = null,
    val materialName: String? = null,
    val nbt: Set<String>? = null,
    val hasModelData: Boolean? = null,
    val isSfItem: Boolean? = null,
) {
    private val materialPattern: Regex? =
        materialName?.let { expression ->
            require(expression.isNotBlank()) { "Material pattern must not be blank" }
            Regex(expression, RegexOption.IGNORE_CASE)
        }

    fun matches(stack: ItemStack): Boolean {
        if (stack.type == material) return true

        if (!nbt.isNullOrEmpty()) {
            val itemNbt = NBT.readNbt(stack)
            if (matchesCustomNbtTags(itemNbt::hasTag)) return true
        }

        if (materialPattern?.matches(stack.type.name) == true) return true

        if (hasModelData != null && stack.itemMeta?.hasCustomModelDataSafe() == hasModelData) {
            return true
        }

        val slimefunHook = HookRegistry.sfHook
        if (isSfItem != null && slimefunHook != null) {
            if (slimefunHook.isSlimefunItem(stack) == isSfItem) return true
        }

        return false
    }

    internal fun matchesCustomNbtTags(hasTag: (String) -> Boolean): Boolean =
        !nbt.isNullOrEmpty() && nbt.all(hasTag)

    companion object {
        @JvmStatic fun of(material: Material) = ItemMatcher(material = material)
        @JvmStatic fun ofNbt(vararg strings: String) = ItemMatcher(nbt = setOf(*strings))
        @JvmStatic fun ofRegex(regex: String) = ItemMatcher(materialName = regex)
        @JvmStatic fun sfItem(isSfItem: Boolean) = ItemMatcher(isSfItem = isSfItem)
        @JvmStatic fun modelData(hasModelData: Boolean) = ItemMatcher(hasModelData = hasModelData)
    }
}
