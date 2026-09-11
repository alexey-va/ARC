package ru.arc.itemcatalog

import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack

/** Adds authored equipment enchantments without rebuilding the native item. */
internal object RewardItemEnhancer {
    fun enrich(base: ItemStack, enchantments: Map<String, Int>): ItemStack? {
        if (enchantments.isEmpty()) return base.clone()
        val requested = linkedMapOf<Enchantment, Int>()
        for ((id, level) in enchantments) {
            val key = NamespacedKey.fromString(id) ?: return null
            if (key.namespace != NamespacedKey.MINECRAFT) return null
            val enchantment = Enchantment.getByKey(key) ?: return null
            if (requested.put(enchantment, level) != null || level !in enchantment.startLevel..enchantment.maxLevel ||
                !enchantment.canEnchantItem(base)) return null
        }
        val existing = base.itemMeta?.enchants.orEmpty()
        val all = (existing.keys + requested.keys).toList()
        if (requested.keys.any { requestedEnchant ->
                all.any { it != requestedEnchant && (it.conflictsWith(requestedEnchant) || requestedEnchant.conflictsWith(it)) }
            }) return null
        return base.clone().also { result ->
            result.editMeta { meta ->
                requested.forEach { (enchantment, level) ->
                    meta.addEnchant(enchantment, maxOf(level, existing[enchantment] ?: 0), false)
                }
            }
        }
    }
}
