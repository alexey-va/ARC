package ru.arc.onboarding

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Lands special-item markers, not the display name or the block material. */
internal object ClaimBlockIdentity {
    private val type = NamespacedKey("lands", "type")
    private val radiusKey = NamespacedKey("lands", "radius")

    fun matches(item: ItemStack): Boolean = radius(item) != null

    // Normal kit blocks use 0 or 1; bound special-item geometry to at most 81 chunks.
    fun radius(item: ItemStack): Int? {
        if (!item.hasItemMeta()) return null
        val data = item.itemMeta.persistentDataContainer
        if (data.get(type, PersistentDataType.STRING) != "CLAIM_BLOCK") return null
        return data.get(radiusKey, PersistentDataType.INTEGER)?.takeIf { it in 0..4 }
    }
}
